package com.retail.agent.context;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Ordered, checksummed classpath migrations; DBA/owner connection only, never the runtime pool. */
public final class PostgresMigrations {
    private PostgresMigrations() {}
    public static void bootstrap(DataSource admin) {
        try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
            statement.execute(resource("bootstrap.sql"));
        } catch (Exception failure) { throw new IllegalStateException("Memory role bootstrap failed", failure); }
    }

    public static void migrate(DataSource owner) {
        apply(owner,1,"V001__persistent_memory.sql");
    }

    /** Additive, explicitly selected upgrade; Phase 6.2 databases need not install pgvector. */
    public static void migrateSemantic(DataSource owner) {
        migrate(owner);
        try(var connection=owner.getConnection();var statement=connection.createStatement();
            var rows=statement.executeQuery("SELECT 1 FROM pg_extension e JOIN pg_namespace n ON n.oid=e.extnamespace WHERE e.extname='vector' AND n.nspname='public'")) {
            if(!rows.next()) { throw new IllegalStateException("DBA must install pgvector in schema public before semantic migration"); }
        } catch(java.sql.SQLException failure) { throw new IllegalStateException("Cannot verify pgvector extension",failure); }
        apply(owner,2,"V002__semantic_retrieval.sql");
    }

    public static void migrateGovernance(DataSource owner) {
        migrateSemantic(owner);
        apply(owner,3,"V003__memory_governance.sql");
    }
    public static void migrateDocuments(DataSource owner){migrateGovernance(owner);apply(owner,4,"V004__enterprise_documents.sql");}
    public static void migrateProductCatalog(DataSource owner){migrateDocuments(owner);apply(owner,5,"V005__product_catalog.sql");}

    private static void apply(DataSource owner,int version,String migration) {
        try (var connection = owner.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(62191307)");
                statement.execute("SET LOCAL ROLE retail_memory_owner");
                statement.execute("CREATE TABLE IF NOT EXISTS retail_memory.schema_history (version integer PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz NOT NULL DEFAULT clock_timestamp())");
                String sql = resource(migration);
                String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)));
                try (var read = connection.prepareStatement("SELECT checksum FROM retail_memory.schema_history WHERE version = ?")) {
                    read.setInt(1, version);
                    try (var rows = read.executeQuery()) {
                        if (rows.next()) {
                            if (!rows.getString(1).equals(checksum)) { throw new IllegalStateException("Applied migration checksum mismatch"); }
                        } else {
                            statement.execute(sql);
                            try (var insert = connection.prepareStatement("INSERT INTO retail_memory.schema_history(version,checksum) VALUES (?,?)")) {
                                insert.setInt(1, version); insert.setString(2, checksum); insert.executeUpdate();
                            }
                        }
                    }
                }
                connection.commit();
            } catch (Exception failure) { connection.rollback(); throw failure; }
        } catch (Exception failure) { throw new IllegalStateException("Memory migration failed", failure); }
    }

    static String resource(String name) throws java.io.IOException {
        try (var stream = PostgresMigrations.class.getResourceAsStream("/db/memory/" + name)) {
            if (stream == null) { throw new IllegalArgumentException("Missing memory migration"); }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

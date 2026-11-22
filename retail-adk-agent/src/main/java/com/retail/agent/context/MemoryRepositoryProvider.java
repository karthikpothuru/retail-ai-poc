package com.retail.agent.context;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.util.function.Function;

/** Environment configuration is opt-in; default selection never opens a database. */
public final class MemoryRepositoryProvider {
    private MemoryRepositoryProvider() {}
    public record Handle(MemoryRepository repository, AutoCloseable resource) implements AutoCloseable {
        @Override public void close() {
            try { resource.close(); } catch (Exception failure) { throw new IllegalStateException("Memory pool close failed", failure); }
        }
    }
    public static Handle open(Clock clock, MemoryWritePolicy policy, ContextAuditLog audit) {
        return open(System::getenv, clock, policy, audit);
    }
    static Handle open(Function<String,String> env, Clock clock, MemoryWritePolicy policy, ContextAuditLog audit) {
        String selection = value(env,"MEMORY_REPOSITORY","in-memory");
        if (selection.equals("in-memory")) { return new Handle(new InMemoryMemoryRepository(clock,policy,audit), () -> {}); }
        if (!selection.equals("postgres")) { throw new IllegalArgumentException("Unsupported MEMORY_REPOSITORY selection"); }
        if (value(env,"MEMORY_POSTGRES_MIGRATE","false").equals("true")) {
            try (var migrationPool = pool(env,true)) { PostgresMigrations.migrate(migrationPool); }
        }
        var pool = pool(env,false);
        try { return new Handle(new PostgresMemoryRepository(pool,clock,policy,audit), pool); }
        catch (RuntimeException failure) { pool.close(); throw failure; }
    }

    public static HikariDataSource pool(Function<String,String> env, boolean migration) {
        String prefix = migration ? "MEMORY_POSTGRES_MIGRATION_" : "MEMORY_POSTGRES_";
        String url = required(env,prefix+"URL");
        if (!url.startsWith("jdbc:postgresql://") || url.matches("(?is).*[?&](user|password)=.*")) {
            throw new IllegalArgumentException("Use a PostgreSQL JDBC URL without embedded credentials");
        }
        HikariConfig config = new HikariConfig();
config.setDriverClassName("org.postgresql.Driver");
        config.setJdbcUrl(url); config.setUsername(required(env,prefix+"USER")); config.setPassword(required(env,prefix+"PASSWORD"));
        int size = integer(env,"MEMORY_POSTGRES_POOL_SIZE",4,1,32);
        config.setMaximumPoolSize(migration ? 1 : size); config.setMinimumIdle(0);
        config.setConnectionTimeout(integer(env,"MEMORY_POSTGRES_CONNECTION_TIMEOUT_MS",10000,250,120000));
        config.setPoolName(migration ? "memory-migrations" : "memory-runtime");
        config.addDataSourceProperty("tcpKeepAlive","true");
        config.setInitializationFailTimeout(-1); // avoid credentials/URL diagnostics during object construction
        return new HikariDataSource(config);
    }
    private static int integer(Function<String,String> env,String key,int fallback,int min,int max) {
        int number;
        try { number=Integer.parseInt(value(env,key,Integer.toString(fallback))); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid numeric memory database configuration"); }
        if(number<min||number>max) { throw new IllegalArgumentException("Memory pool setting is out of range"); }
        return number;
    }
    private static String required(Function<String,String> env,String key) {
        String value=env.apply(key);
        if(value==null||value.isBlank()) { throw new IllegalArgumentException("Required memory database environment setting is missing: "+key); }
        return value;
    }
    private static String value(Function<String,String> env,String key,String fallback) {
        String value=env.apply(key); return value==null||value.isBlank()?fallback:value;
    }
}

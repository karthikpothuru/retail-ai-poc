package com.retail.agent.context;

/** Explicit DBA bootstrap/migration entry point; never invoked by existing agent CLIs. */
public final class PostgresMemoryAdminCliRunner {
    private PostgresMemoryAdminCliRunner() {}
    public static void main(String[] args) {
        if(args.length!=1 || (!args[0].equals("bootstrap")&&!args[0].equals("migrate")&&!args[0].equals("migrate-semantic")&&!args[0].equals("migrate-governance")&&!args[0].equals("migrate-documents")&&!args[0].equals("migrate-product-catalog"))) {
            System.out.println("Usage: PostgresMemoryAdminCliRunner bootstrap|migrate|migrate-semantic|migrate-governance|migrate-documents|migrate-product-catalog"); return;
        }
        try(var pool=MemoryRepositoryProvider.pool(System::getenv,true)) {
            if(args[0].equals("bootstrap")) { PostgresMigrations.bootstrap(pool); }
            else if(args[0].equals("migrate-product-catalog")) { PostgresMigrations.migrateProductCatalog(pool); }
            else if(args[0].equals("migrate-documents")) { PostgresMigrations.migrateDocuments(pool); }
            else if(args[0].equals("migrate-governance")) { PostgresMigrations.migrateGovernance(pool); }
            else if(args[0].equals("migrate-semantic")) { PostgresMigrations.migrateSemantic(pool); }
            else { PostgresMigrations.migrate(pool); }
            System.out.println("Memory database "+args[0]+" completed.");
        } catch(RuntimeException failure) {
            // Connection exceptions/configuration may contain URLs. Keep command output credential-free.
            System.err.println("Memory database administration failed; verify configuration and DBA permissions.");
            throw new IllegalStateException("Memory database administration failed");
        }
    }
}

package com.retail.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.sql.Connection;

/** Transaction-local claims are supplied only by authenticated host code, not database clients or prompts. */
final class PostgresTransactions {
    static final ObjectMapper JSON = new ObjectMapper();
    interface Work<T> { T run(Connection connection) throws Exception; }
    private PostgresTransactions() {}

    static <T> T inScope(DataSource pool, ContextIdentity.Access access, Work<T> work) {
        access.authorize();
        try (var connection = pool.getConnection()) {
            // Clear any session-level GUC poison from an earlier borrower, including aborted operations.
            connection.setAutoCommit(true);
            try (var reset = connection.createStatement()) { reset.execute("RESET ALL"); }
            connection.setAutoCommit(false);
            try {
                bind(connection, access);
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (Exception failure) {
                connection.rollback();
                if (failure instanceof RuntimeException runtime) { throw runtime; }
                throw new IllegalStateException("PostgreSQL memory transaction failed", failure);
            } finally {
                connection.setAutoCommit(true);
                try (var reset = connection.createStatement()) { reset.execute("RESET ALL"); }
            }
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("PostgreSQL memory connection failed", failure); }
    }

    static void bind(Connection connection, ContextIdentity.Access access) throws Exception {
        access.authorize();
        try (var statement = connection.prepareStatement("SELECT set_config('retail.tenant_id',?,true),set_config('retail.user_id',?,true),set_config('retail.session_id',?,true),set_config('retail.task_id',?,true),set_config('retail.clearance',?,true),set_config('retail.capabilities',?,true)")) {
            statement.setString(1, access.identity().tenantId()); statement.setString(2, access.identity().userId());
            statement.setString(3, access.sessionId() == null ? "" : access.sessionId());
            statement.setString(4, access.taskId() == null ? "" : access.taskId());
            statement.setString(5, Integer.toString(access.identity().clearance().ordinal()));
            statement.setString(6, JSON.writeValueAsString(access.identity().capabilities().stream().map(Enum::name).sorted().toList()));
            statement.execute();
        }
        try (var statement = connection.createStatement()) { statement.execute("SET LOCAL statement_timeout = '15s'"); }
    }

    static void requireRole(DataSource pool, String role) {
        try (var connection = pool.getConnection(); var statement = connection.prepareStatement("SELECT rolsuper,rolbypassrls,rolcreaterole,rolcreatedb,pg_has_role(current_user,'retail_memory_owner','MEMBER'),pg_has_role(current_user,?,'MEMBER'),pg_has_role(current_user,'retail_memory_retention','MEMBER'),pg_has_role(current_user,'retail_memory_app','MEMBER'),pg_has_role(current_user,'pg_write_all_data','MEMBER'),EXISTS(SELECT 1 FROM pg_roles a WHERE a.rolname='retail_memory_audit_writer' AND pg_has_role(current_user,a.oid,'MEMBER')) FROM pg_roles WHERE rolname = current_user")) {
            statement.setString(1, role);
            try (var result = statement.executeQuery()) {
                if (!result.next() || result.getBoolean(1) || result.getBoolean(2) || result.getBoolean(3)
                        || result.getBoolean(4) || result.getBoolean(5) || !result.getBoolean(6) || result.getBoolean(9)
                        || (role.equals("retail_memory_app") && (result.getBoolean(7)||result.getBoolean(10)))
                        || (role.equals("retail_memory_retention") && (result.getBoolean(8)||result.getBoolean(10)))
                        || (role.equals("retail_memory_audit_writer") && (result.getBoolean(7)||result.getBoolean(8)))) {
                    throw new SecurityException("Memory connections require a least-privilege non-owner database login");
                }
            }
        } catch (java.sql.SQLException failure) { throw new IllegalStateException("Cannot validate memory database role", failure); }
    }
}

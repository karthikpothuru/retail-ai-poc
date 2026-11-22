package com.retail.agent.context;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Separate database retention role; runtime pool cannot release legal holds or change retention. */
public final class PostgresRetentionService {
    private final DataSource pool;
    public PostgresRetentionService(DataSource pool) {
        this.pool = pool;
        PostgresTransactions.requireRole(pool, "retail_memory_retention");
    }
    public void configure(ContextIdentity.Access access, UUID memoryId, long expectedVersion,
                          boolean legalHold, Instant retainUntil, String policyId) {
        ContextIdentity.identifier(policyId);
        PostgresTransactions.inScope(pool, access, connection -> {
            try (var statement = connection.prepareStatement("UPDATE retail_memory.memory_heads SET legal_hold=?,retain_until=?,retention_policy=? WHERE memory_id=? AND current_version=?")) {
                statement.setBoolean(1, legalHold); statement.setTimestamp(2, retainUntil == null ? null : Timestamp.from(retainUntil));
                statement.setString(3, policyId); statement.setObject(4, memoryId); statement.setLong(5, expectedVersion);
                if (statement.executeUpdate() != 1) { throw new java.util.ConcurrentModificationException("Retention target not accessible or version changed"); }
            }
            PostgresMemoryRepository.durableAudit(connection, access, legalHold ? "LEGAL_HOLD_SET" : "RETENTION_CONFIGURED", memoryId, expectedVersion, 1);
            return null;
        });
    }
}

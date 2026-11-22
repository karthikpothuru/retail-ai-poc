package com.retail.agent.context;

import com.fasterxml.jackson.core.type.TypeReference;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import static com.retail.agent.context.MemoryTypes.*;

/** Phase 6.1 contract with forced RLS and transactional, content-free durable audit events. */
public final class PostgresMemoryRepository implements MemoryRepository {
    private static final String CURRENT = "SELECT v.* FROM retail_memory.memory_heads h JOIN retail_memory.memory_versions v ON (h.tenant_id,h.user_id,h.memory_id,h.current_version)=(v.tenant_id,v.user_id,v.memory_id,v.version) ";
    private final DataSource pool;
    private final Clock clock;
    private final MemoryWritePolicy policy;
    private final ContextAuditLog audit;

    public PostgresMemoryRepository(DataSource pool, Clock clock, MemoryWritePolicy policy, ContextAuditLog audit) {
        this.pool = Objects.requireNonNull(pool); this.clock = Objects.requireNonNull(clock);
        this.policy = Objects.requireNonNull(policy); this.audit = Objects.requireNonNull(audit);
        PostgresTransactions.requireRole(pool, "retail_memory_app");
    }

    @Override public MemoryRecord create(ContextIdentity.Access access, MemoryRecord.Draft draft) {
        return operation(access, "MEMORY_CREATE", connection -> {
            var source = policy.validate(access, draft);
            var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var record = new MemoryRecord(UUID.randomUUID(), draft.type(), ContextIdentity.Scope.from(access), draft.content(),
                    draft.metadata(), source, now, now, precision(draft.expiresAt()), draft.verification(), draft.sensitivity(), 1, null, Lifecycle.ACTIVE);
            try (var statement = connection.prepareStatement("INSERT INTO retail_memory.memory_heads(tenant_id,user_id,memory_id,session_id,task_id,memory_type,current_version,verification,sensitivity,lifecycle,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, record.scope().tenantId()); statement.setString(2, record.scope().userId()); statement.setObject(3, record.id());
                statement.setString(4, record.scope().sessionId()); statement.setString(5, record.scope().taskId()); statement.setString(6, record.type().name());
                statement.setLong(7, 1); statement.setString(8, record.verification().name()); statement.setInt(9, record.sensitivity().ordinal());
                statement.setString(10, record.lifecycle().name()); statement.setTimestamp(11, timestamp(record.expiresAt())); statement.executeUpdate();
            }
            insertVersion(connection, record);
            durableAudit(connection, access, "MEMORY_CREATE", record.id(), record.version(), 1);
            return record;
        });
    }

    @Override public Optional<MemoryRecord> read(ContextIdentity.Access access, UUID id) {
        return operation(access, "MEMORY_READ", connection -> {
            var record = current(connection, id, false);
            var result = record.filter(memory -> memory.activeAt(clock.instant()) && memory.scope().permits(access)
                    && memory.sensitivity().ordinal() <= access.identity().clearance().ordinal());
            durableAudit(connection, access, "MEMORY_READ", null, null, result.isPresent() ? 1 : 0);
            return result;
        });
    }

    @Override public List<MemoryRecord> search(ContextIdentity.Access access, Search query) {
        return operation(access, "MEMORY_SEARCH", connection -> {
            List<MemoryRecord> results = new ArrayList<>();
            try (var statement = connection.prepareStatement(CURRENT + "WHERE h.lifecycle='ACTIVE' AND v.lifecycle='ACTIVE' AND (v.expires_at IS NULL OR v.expires_at > ?) AND strpos(lower(v.content),?) > 0 AND (? OR v.memory_type = ANY(?)) ORDER BY v.updated_at DESC,v.memory_id LIMIT ?")) {
                statement.setTimestamp(1, timestamp(clock.instant())); statement.setString(2, query.text().toLowerCase(Locale.ROOT));
                statement.setBoolean(3, query.types().isEmpty());
                statement.setArray(4, connection.createArrayOf("text", query.types().stream().map(Enum::name).toArray()));
                statement.setInt(5, query.limit());
                try (var rows = statement.executeQuery()) { while (rows.next()) { results.add(decode(rows)); } }
            }
            durableAudit(connection, access, "MEMORY_SEARCH", null, null, results.size());
            return List.copyOf(results);
        });
    }

    @Override public MemoryRecord update(ContextIdentity.Access access, UUID id, long version, MemoryRecord.Draft draft) {
        return operation(access, "MEMORY_UPDATE", connection -> {
            var old = writable(connection, access, id, version);
            if (!old.activeAt(clock.instant()) || old.type() != draft.type()) {
                throw new IllegalStateException("Cannot update inactive memory or change its category");
            }
            var original = new ContextIdentity.Access(access.identity(), old.scope().sessionId(), old.scope().taskId());
            var source = policy.validate(original, draft);
            var next = new MemoryRecord(id, old.type(), old.scope(), draft.content(), draft.metadata(), source,
                    old.createdAt(), precision(clock.instant()), precision(draft.expiresAt()), draft.verification(), draft.sensitivity(), version + 1, version, Lifecycle.ACTIVE);
            append(connection, old, next);
            durableAudit(connection, access, "MEMORY_UPDATE", id, next.version(), 1);
            return next;
        });
    }

    @Override public MemoryRecord revoke(ContextIdentity.Access access, UUID id, long version) {
        return operation(access, "MEMORY_REVOKE", connection -> {
            var old = writable(connection, access, id, version);
            if (old.lifecycle() != Lifecycle.ACTIVE) { throw new IllegalStateException("Memory already inactive"); }
            var next = new MemoryRecord(id, old.type(), old.scope(), old.content(), old.metadata(), old.provenance(), old.createdAt(),
                    precision(clock.instant()), old.expiresAt(), old.verification(), old.sensitivity(), version + 1, version, Lifecycle.REVOKED);
            append(connection, old, next);
            durableAudit(connection, access, "MEMORY_REVOKE", id, next.version(), 1);
            return next;
        });
    }

    @Override public void delete(ContextIdentity.Access access, UUID id, long version) {
        operation(access, "MEMORY_DELETE", connection -> {
            writable(connection, access, id, version);
            deleteHead(connection, id);
            durableAudit(connection, access, "MEMORY_DELETE", id, version, 1);
            return null;
        });
    }

    /** Bounded, owner/scope/capability-authorized cleanup; legal holds and retention are never bypassed. */
    public int cleanupExpired(ContextIdentity.Access access, int limit) {
        if (limit < 1 || limit > 1000) { throw new IllegalArgumentException("Invalid cleanup limit"); }
        return operation(access, "MEMORY_CLEANUP", connection -> {
            MemoryWritePolicy.authorizeWrite(access);
            List<MemoryRecord> expired = new ArrayList<>();
            try (var statement = connection.prepareStatement(CURRENT + "WHERE h.expires_at <= ? AND NOT h.legal_hold AND (h.retain_until IS NULL OR h.retain_until <= clock_timestamp()) ORDER BY h.expires_at,h.memory_id LIMIT ? FOR UPDATE OF h SKIP LOCKED")) {
                statement.setTimestamp(1, timestamp(clock.instant())); statement.setInt(2, limit);
                try (var rows = statement.executeQuery()) { while (rows.next()) { expired.add(decode(rows)); } }
            }
            int count = 0;
            for (var memory : expired) {
                if (!canChange(access, memory)) { continue; }
                deleteHead(connection, memory.id());
                durableAudit(connection, access, "MEMORY_EXPIRED_DELETE", memory.id(), memory.version(), 1);
                count++;
            }
            return count;
        });
    }

    private Optional<MemoryRecord> current(Connection connection, UUID id, boolean lock) throws Exception {
        try (var statement = connection.prepareStatement(CURRENT + "WHERE h.memory_id=?" + (lock ? " FOR UPDATE OF h" : ""))) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(decode(rows)) : Optional.empty(); }
        }
    }

    private MemoryRecord writable(Connection connection, ContextIdentity.Access access, UUID id, long version) throws Exception {
        MemoryWritePolicy.authorizeWrite(access);
        // Lock the head first, then read the joined revision in a fresh READ COMMITTED statement snapshot.
        // This avoids returning a pre-lock joined revision when another writer advanced the head.
        try (var lock = connection.prepareStatement("SELECT current_version FROM retail_memory.memory_heads WHERE memory_id=? FOR UPDATE")) {
            lock.setObject(1,id);
            try(var rows=lock.executeQuery()) { if(!rows.next()) { throw new SecurityException("Memory not accessible"); } }
        }
        var memory = current(connection, id, false).orElseThrow(() -> new SecurityException("Memory not accessible"));
        if (!memory.scope().permits(access) || !canChange(access, memory)) { throw new SecurityException("Authoritative memory changes not authorized"); }
        if (memory.version() != version) { throw new java.util.ConcurrentModificationException("Memory version conflict"); }
        return memory;
    }

    private static boolean canChange(ContextIdentity.Access access, MemoryRecord memory) {
        return (memory.type() != Type.PROCEDURAL || access.identity().capabilities().contains(Capability.APPROVE_PROCEDURE))
                && (memory.type() != Type.SEMANTIC || memory.verification() != Verification.VERIFIED
                || access.identity().capabilities().contains(Capability.VERIFY_SEMANTIC));
    }

    private void append(Connection connection, MemoryRecord old, MemoryRecord next) throws Exception {
        try (var statement = connection.prepareStatement("UPDATE retail_memory.memory_versions SET lifecycle='SUPERSEDED' WHERE memory_id=? AND version=?")) {
            statement.setObject(1, old.id()); statement.setLong(2, old.version());
            if (statement.executeUpdate() != 1) { throw new java.util.ConcurrentModificationException("Revision conflict"); }
        }
        insertVersion(connection, next);
        try (var statement = connection.prepareStatement("UPDATE retail_memory.memory_heads SET current_version=?,verification=?,sensitivity=?,lifecycle=?,expires_at=? WHERE memory_id=? AND current_version=?")) {
            statement.setLong(1, next.version()); statement.setString(2, next.verification().name()); statement.setInt(3, next.sensitivity().ordinal());
            statement.setString(4, next.lifecycle().name()); statement.setTimestamp(5, timestamp(next.expiresAt()));
            statement.setObject(6, next.id()); statement.setLong(7, old.version());
            if (statement.executeUpdate() != 1) { throw new java.util.ConcurrentModificationException("Memory version conflict"); }
        }
    }

    private static void deleteHead(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("DELETE FROM retail_memory.memory_heads WHERE memory_id=?")) {
            statement.setObject(1, id);
            if (statement.executeUpdate() != 1) { throw new SecurityException("Memory not accessible"); }
        }
    }

    private static void insertVersion(Connection connection, MemoryRecord r) throws Exception {
        try (var s = connection.prepareStatement("INSERT INTO retail_memory.memory_versions(tenant_id,user_id,memory_id,version,session_id,task_id,memory_type,content,metadata,source_id,source_tenant_id,source_user_id,source_origin,source_citation,created_at,updated_at,expires_at,verification,sensitivity,supersedes_version,lifecycle) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            s.setString(1,r.scope().tenantId()); s.setString(2,r.scope().userId()); s.setObject(3,r.id()); s.setLong(4,r.version());
            s.setString(5,r.scope().sessionId()); s.setString(6,r.scope().taskId()); s.setString(7,r.type().name()); s.setString(8,r.content());
            s.setString(9,PostgresTransactions.JSON.writeValueAsString(r.metadata())); s.setString(10,r.provenance().id());
            s.setString(11,r.provenance().tenantId()); s.setString(12,r.provenance().ownerUserId()); s.setString(13,r.provenance().origin().name());
            s.setString(14,r.provenance().citation()); s.setTimestamp(15,timestamp(r.createdAt())); s.setTimestamp(16,timestamp(r.updatedAt()));
            s.setTimestamp(17,timestamp(r.expiresAt())); s.setString(18,r.verification().name()); s.setInt(19,r.sensitivity().ordinal());
            s.setObject(20,r.supersedesVersion()); s.setString(21,r.lifecycle().name()); s.executeUpdate();
        }
    }

    static void durableAudit(Connection c, ContextIdentity.Access access, String action, UUID id, Long version, int count) throws Exception {
        GovernanceAuditScope.success(c,access,action,id,version);
        try (var s = c.prepareStatement("INSERT INTO retail_memory.audit_events(event_id,tenant_id,user_id,session_id,task_id,occurred_at,action,outcome,memory_id,memory_version,affected_count) VALUES (?,?,?,?,?,clock_timestamp(),?,'SUCCESS',?,?,?)")) {
            s.setObject(1,UUID.randomUUID()); s.setString(2,access.identity().tenantId()); s.setString(3,access.identity().userId());
            s.setString(4,access.sessionId()); s.setString(5,access.taskId()); s.setString(6,action); s.setObject(7,id); s.setObject(8,version); s.setInt(9,count); s.executeUpdate();
        }
    }

    static MemoryRecord decode(ResultSet r) throws Exception {
        return new MemoryRecord(r.getObject("memory_id",UUID.class),Type.valueOf(r.getString("memory_type")),
                new ContextIdentity.Scope(r.getString("tenant_id"),r.getString("user_id"),r.getString("session_id"),r.getString("task_id")),
                r.getString("content"),PostgresTransactions.JSON.readValue(r.getString("metadata"),new TypeReference<>() {}),
                new SourceRegistry.Source(r.getString("source_id"),r.getString("source_tenant_id"),r.getString("source_user_id"),
                        Origin.valueOf(r.getString("source_origin")),r.getString("source_citation")),
                instant(r,"created_at"),instant(r,"updated_at"),instant(r,"expires_at"),Verification.valueOf(r.getString("verification")),
                Sensitivity.values()[r.getInt("sensitivity")],r.getLong("version"),r.getObject("supersedes_version",Long.class),Lifecycle.valueOf(r.getString("lifecycle")));
    }

    private <T> T operation(ContextIdentity.Access access, String action, PostgresTransactions.Work<T> work) {
        try {
            T result = PostgresTransactions.inScope(pool, access, work);
            audit.record(access,action,"SUCCESS",null,0); // local mirror only after database commit
            return result;
        } catch (RuntimeException failure) {
            audit.record(access,action,failure instanceof SecurityException ? "DENIED" : "REJECTED",null,0);
            throw failure;
        }
    }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant precision(Instant value) { return value == null ? null : value.truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private static Instant instant(ResultSet rows,String name) throws Exception { var value=rows.getTimestamp(name); return value==null?null:value.toInstant(); }
}

package com.retail.agent.context;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import static com.retail.agent.context.MemoryTypes.*;

/** Serialized atomic operations; immutable revisions can safely be returned to concurrent readers. */
public final class InMemoryMemoryRepository implements MemoryRepository {
    private final Map<UUID, List<MemoryRecord>> revisions = new HashMap<>();
    private final Clock clock;
    private final MemoryWritePolicy policy;
    private final ContextAuditLog audit;

    public InMemoryMemoryRepository(Clock clock, MemoryWritePolicy policy, ContextAuditLog audit) {
        this.clock = java.util.Objects.requireNonNull(clock);
        this.policy = java.util.Objects.requireNonNull(policy);
        this.audit = java.util.Objects.requireNonNull(audit);
    }

    @Override
    public synchronized MemoryRecord create(ContextIdentity.Access access, MemoryRecord.Draft draft) {
        return operation(access, "MEMORY_CREATE", () -> {
            var source = policy.validate(access, draft);
            var now = clock.instant();
            var record = new MemoryRecord(UUID.randomUUID(), draft.type(), ContextIdentity.Scope.from(access),
                    draft.content(), draft.metadata(), source, now, now, draft.expiresAt(), draft.verification(),
                    draft.sensitivity(), 1, null, Lifecycle.ACTIVE);
            revisions.put(record.id(), new ArrayList<>(List.of(record)));
            audit.record(access, "MEMORY_CREATE", "SUCCESS", record.id(), 1);
            return record;
        });
    }

    @Override
    public synchronized Optional<MemoryRecord> read(ContextIdentity.Access access, UUID id) {
        return operation(access, "MEMORY_READ", () -> {
            access.authorize();
            var latest = latest(id);
            var result = latest != null && visible(access, latest) && latest.activeAt(clock.instant())
                    ? Optional.of(latest) : Optional.<MemoryRecord>empty();
            audit.record(access, "MEMORY_READ", result.isPresent() ? "FOUND" : "NOT_VISIBLE", null, result.isPresent() ? 1 : 0);
            return result;
        });
    }

    @Override
    public synchronized List<MemoryRecord> search(ContextIdentity.Access access, Search query) {
        return operation(access, "MEMORY_SEARCH", () -> {
            access.authorize();
            var now = clock.instant();
            var results = revisions.values().stream().map(history -> history.get(history.size() - 1))
                    .filter(memory -> visible(access, memory) && memory.activeAt(now))
                    .filter(memory -> query.types().isEmpty() || query.types().contains(memory.type()))
                    .filter(memory -> memory.content().toLowerCase(Locale.ROOT).contains(query.text().toLowerCase(Locale.ROOT)))
                    .sorted(Comparator.comparing(MemoryRecord::updatedAt).reversed().thenComparing(memory -> memory.id().toString()))
                    .limit(query.limit()).toList();
            audit.record(access, "MEMORY_SEARCH", "SUCCESS", null, results.size());
            return results;
        });
    }

    @Override
    public synchronized MemoryRecord update(ContextIdentity.Access access, UUID id, long expectedVersion, MemoryRecord.Draft draft) {
        return operation(access, "MEMORY_UPDATE", () -> {
            var old = writable(access, id, expectedVersion);
            if (!old.activeAt(clock.instant()) || old.type() != draft.type()) {
                throw new IllegalStateException("Cannot update inactive memory or change its category");
            }
            // Scope is immutable; validate working requirements against the original scope as well.
            var originAccess = new ContextIdentity.Access(access.identity(), old.scope().sessionId(), old.scope().taskId());
            var source = policy.validate(originAccess, draft);
            var replacement = new MemoryRecord(id, old.type(), old.scope(), draft.content(), draft.metadata(), source,
                    old.createdAt(), clock.instant(), draft.expiresAt(), draft.verification(), draft.sensitivity(),
                    expectedVersion + 1, expectedVersion, Lifecycle.ACTIVE);
            append(old, replacement);
            audit.record(access, "MEMORY_UPDATE", "SUCCESS", id, 1);
            return replacement;
        });
    }

    @Override
    public synchronized MemoryRecord revoke(ContextIdentity.Access access, UUID id, long expectedVersion) {
        return operation(access, "MEMORY_REVOKE", () -> {
            var old = writable(access, id, expectedVersion);
            if (old.lifecycle() != Lifecycle.ACTIVE) { throw new IllegalStateException("Memory already inactive"); }
            var revoked = new MemoryRecord(id, old.type(), old.scope(), old.content(), old.metadata(), old.provenance(),
                    old.createdAt(), clock.instant(), old.expiresAt(), old.verification(), old.sensitivity(),
                    expectedVersion + 1, expectedVersion, Lifecycle.REVOKED);
            append(old, revoked);
            audit.record(access, "MEMORY_REVOKE", "SUCCESS", id, 1);
            return revoked;
        });
    }

    @Override
    public synchronized void delete(ContextIdentity.Access access, UUID id, long expectedVersion) {
        operation(access, "MEMORY_DELETE", () -> {
            writable(access, id, expectedVersion);
            revisions.remove(id);
            audit.record(access, "MEMORY_DELETE", "SUCCESS", id, 1);
            return null;
        });
    }

    private MemoryRecord writable(ContextIdentity.Access access, UUID id, long expectedVersion) {
        MemoryWritePolicy.authorizeWrite(access);
        var memory = latest(id);
        if (memory == null || !visible(access, memory)) { throw new SecurityException("Memory not accessible"); }
        if ((memory.type() == Type.PROCEDURAL && !access.identity().capabilities().contains(Capability.APPROVE_PROCEDURE))
                || (memory.type() == Type.SEMANTIC && memory.verification() == Verification.VERIFIED
                && !access.identity().capabilities().contains(Capability.VERIFY_SEMANTIC))) {
            throw new SecurityException("Changing authoritative memory requires its approval authority");
        }
        if (memory.version() != expectedVersion) { throw new java.util.ConcurrentModificationException("Memory version conflict"); }
        return memory;
    }

    private void append(MemoryRecord old, MemoryRecord replacement) {
        var history = revisions.get(old.id());
        history.set(history.size() - 1, new MemoryRecord(old.id(), old.type(), old.scope(), old.content(), old.metadata(),
                old.provenance(), old.createdAt(), old.updatedAt(), old.expiresAt(), old.verification(), old.sensitivity(),
                old.version(), old.supersedesVersion(), Lifecycle.SUPERSEDED));
        history.add(replacement);
    }

    private MemoryRecord latest(UUID id) {
        var history = revisions.get(id);
        return history == null ? null : history.get(history.size() - 1);
    }

    private static boolean visible(ContextIdentity.Access access, MemoryRecord record) {
        return record.scope().permits(access) && record.sensitivity().ordinal() <= access.identity().clearance().ordinal();
    }

    private <T> T operation(ContextIdentity.Access access, String action, Supplier<T> operation) {
        try { return operation.get(); }
        catch (RuntimeException failure) {
            audit.record(access, action, failure instanceof SecurityException ? "DENIED" : "REJECTED", null, 0);
            throw failure;
        }
    }
}

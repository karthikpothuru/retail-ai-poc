package com.retail.agent.context;

import java.util.UUID;

/** Explicit opt-in writes only. No conversation ingestion or automatic persistence. */
public final class MemoryWriteService {
    private final MemoryRepository repository;
    private final MemoryWritePolicy policy;
    private final ContextAuditLog audit;
    public MemoryWriteService(MemoryRepository repository, MemoryWritePolicy policy, ContextAuditLog audit) {
        this.repository = java.util.Objects.requireNonNull(repository);
        this.policy = java.util.Objects.requireNonNull(policy);
        this.audit = java.util.Objects.requireNonNull(audit);
    }

    public MemoryRecord write(ContextIdentity.Access access, MemoryRecord.Draft draft) {
        validate(access, draft);
        return repository.create(access, draft);
    }

    public MemoryRecord update(ContextIdentity.Access access, UUID id, long expectedVersion, MemoryRecord.Draft draft) {
        validate(access, draft);
        return repository.update(access, id, expectedVersion, draft);
    }

    public MemoryRecord revoke(ContextIdentity.Access access, UUID id, long expectedVersion) {
        return repository.revoke(access, id, expectedVersion);
    }

    public void delete(ContextIdentity.Access access, UUID id, long expectedVersion) {
        repository.delete(access, id, expectedVersion);
    }

    private void validate(ContextIdentity.Access access, MemoryRecord.Draft draft) {
        try { policy.validate(access, draft); }
        catch (RuntimeException failure) {
            audit.record(access, "MEMORY_WRITE_VALIDATION", failure instanceof SecurityException ? "DENIED" : "REJECTED", null, 0);
            throw failure;
        }
    }
}

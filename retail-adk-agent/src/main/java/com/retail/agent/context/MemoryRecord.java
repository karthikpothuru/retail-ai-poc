package com.retail.agent.context;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import static com.retail.agent.context.MemoryTypes.*;

/** Logical ID is stable across immutable revisions; old revisions become SUPERSEDED. */
public record MemoryRecord(UUID id, Type type, ContextIdentity.Scope scope, String content,
                           Map<String, Object> metadata, SourceRegistry.Source provenance,
                           Instant createdAt, Instant updatedAt, Instant expiresAt,
                           Verification verification, Sensitivity sensitivity, long version,
                           Long supersedesVersion, Lifecycle lifecycle) {
    public MemoryRecord {
        Objects.requireNonNull(id); Objects.requireNonNull(type); Objects.requireNonNull(scope);
        StructuredValues.content(content); metadata = StructuredValues.metadata(metadata);
        Objects.requireNonNull(provenance); Objects.requireNonNull(createdAt); Objects.requireNonNull(updatedAt);
        Objects.requireNonNull(verification); Objects.requireNonNull(sensitivity); Objects.requireNonNull(lifecycle);
        if (version < 1 || updatedAt.isBefore(createdAt)
                || (supersedesVersion != null && supersedesVersion != version - 1)) {
            throw new IllegalArgumentException("Invalid memory revision");
        }
    }

    boolean activeAt(Instant now) {
        return lifecycle == Lifecycle.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }

    public record Draft(Type type, String content, Map<String, Object> metadata, String sourceId,
                        Instant expiresAt, Verification verification, Sensitivity sensitivity) {
        public Draft {
            Objects.requireNonNull(type); StructuredValues.content(content);
            metadata = StructuredValues.metadata(metadata); ContextIdentity.identifier(sourceId);
            Objects.requireNonNull(verification); Objects.requireNonNull(sensitivity);
        }
    }
}

package com.retail.agent.context;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Implementations must enforce scope predicates and optimistic versions atomically at the storage boundary. */
public interface MemoryRepository {
    record Search(Set<MemoryTypes.Type> types, String text, int limit) {
        public Search {
            types = Set.copyOf(types);
            text = text == null ? "" : text;
            if (limit < 1 || limit > 1000 || text.length() > 1000) { throw new IllegalArgumentException("Invalid search bounds"); }
        }
    }
    MemoryRecord create(ContextIdentity.Access access, MemoryRecord.Draft draft);
    Optional<MemoryRecord> read(ContextIdentity.Access access, UUID id);
    List<MemoryRecord> search(ContextIdentity.Access access, Search query);
    MemoryRecord update(ContextIdentity.Access access, UUID id, long expectedVersion, MemoryRecord.Draft draft);
    MemoryRecord revoke(ContextIdentity.Access access, UUID id, long expectedVersion);
    void delete(ContextIdentity.Access access, UUID id, long expectedVersion);
}

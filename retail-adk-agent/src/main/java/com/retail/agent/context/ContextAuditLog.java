package com.retail.agent.context;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded metadata-only audit. Never records content, metadata values, goals or credentials. */
public final class ContextAuditLog {
    public record Entry(UUID eventId, Instant timestamp, String action, String outcome,
                        String tenantId, String userId, UUID memoryId, int count, UUID assemblyId) {}
    private final Clock clock;
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    public ContextAuditLog(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    public synchronized void record(ContextIdentity.Access access, String action, String outcome, UUID memoryId, int count) {
        record(access, action, outcome, memoryId, count, null);
    }

    public synchronized void record(ContextIdentity.Access access, String action, String outcome, UUID memoryId, int count, UUID assemblyId) {
        if (entries.size() == 2000) { entries.removeFirst(); }
        entries.addLast(new Entry(UUID.randomUUID(), clock.instant(), action, outcome,
                access.identity().tenantId(), access.identity().userId(), memoryId, count, assemblyId));
    }

    public synchronized List<Entry> entries() { return List.copyOf(entries); }
}

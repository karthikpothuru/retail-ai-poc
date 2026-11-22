
package com.retail.agent.context;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Bounded, non-durable governance audit for local POC use only.
 * Never use as a production audit sink.
 */
public final class InMemoryGovernanceAudit implements GovernanceAudit {

    private static final int MAX_EVENTS = 2000;

    private final ArrayDeque<Event> events = new ArrayDeque<>();

    @Override
    public synchronized void independent(
            Event event,
            ContextIdentity.Access access) {

        java.util.Objects.requireNonNull(event);

        if ("SUCCESS".equals(event.outcome())) {
            throw new IllegalArgumentException(
                    "Successful mutations require their memory transaction");
        }

        if (events.size() == MAX_EVENTS) {
            events.removeFirst();
        }

        events.addLast(event);
    }

    public synchronized List<Event> events() {
        return List.copyOf(events);
    }
}

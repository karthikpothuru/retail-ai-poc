package com.retail.agent.context;

import java.time.Instant;
import java.util.UUID;

/** Denials use an independent commit or a durable outbox, never the failed memory transaction. */
public interface GovernanceAudit {
    record Event(UUID eventId,Instant occurredAt,String tenantId,String userId,UUID authenticationId,
                 String operation,String outcome,String reason,UUID memoryId,Long memoryVersion) {
        public Event {
            java.util.Objects.requireNonNull(eventId);java.util.Objects.requireNonNull(occurredAt);
            if(tenantId!=null)ContextIdentity.identifier(tenantId);if(userId!=null)ContextIdentity.identifier(userId);
            if(!operation.matches("[A-Z_]{1,40}")||!outcome.matches("SUCCESS|DENIED|ROLLED_BACK")
                    ||!reason.matches("[A-Z_]{1,60}"))throw new IllegalArgumentException("Invalid governance audit vocabulary");
        }
    }
    void independent(Event event,ContextIdentity.Access access);
    static Event event(TrustedCallerBoundary.Caller caller,boolean authenticated,String operation,String outcome,String reason,UUID memoryId,Long version){
        return new Event(UUID.randomUUID(),Instant.now(),authenticated?caller.access().identity().tenantId():null,
                authenticated?caller.access().identity().userId():null,authenticated?caller.authenticationId():null,
                operation,outcome,reason,memoryId,version);
    }
}

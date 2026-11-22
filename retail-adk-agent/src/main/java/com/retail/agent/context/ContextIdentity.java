package com.retail.agent.context;

import java.util.Objects;
import java.util.Set;
import static com.retail.agent.context.MemoryTypes.*;

/** Trusted ingress claim supplied by the host authenticator; never derive this from an LLM or prompt. */
public record ContextIdentity(String tenantId, String userId, Set<String> sessions, Set<String> tasks,
                              Set<Capability> capabilities, Sensitivity clearance) {
    public ContextIdentity {
        tenantId = identifier(tenantId);
        userId = identifier(userId);
        sessions = Set.copyOf(sessions);
        tasks = Set.copyOf(tasks);
        sessions.forEach(ContextIdentity::identifier);
        tasks.forEach(ContextIdentity::identifier);
        capabilities = Set.copyOf(capabilities);
        Objects.requireNonNull(clearance);
    }

    static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("Invalid context identifier");
        }
        return value;
    }

    public record Access(ContextIdentity identity, String sessionId, String taskId) {
        public Access {
            Objects.requireNonNull(identity);
            if (sessionId != null) { identifier(sessionId); }
            if (taskId != null) { identifier(taskId); }
        }

        public void authorize() {
            if ((sessionId != null && !identity.sessions().contains(sessionId))
                    || (taskId != null && !identity.tasks().contains(taskId))) {
                throw new SecurityException("Requested context scope is not authorized");
            }
        }
    }

    public record Scope(String tenantId, String userId, String sessionId, String taskId) {
        public Scope {
            identifier(tenantId); identifier(userId);
            if (sessionId != null) { identifier(sessionId); }
            if (taskId != null) { identifier(taskId); }
        }

        static Scope from(Access access) {
            return new Scope(access.identity().tenantId(), access.identity().userId(), access.sessionId(), access.taskId());
        }

        boolean permits(Access access) {
            return tenantId.equals(access.identity().tenantId()) && userId.equals(access.identity().userId())
                    && (sessionId == null || sessionId.equals(access.sessionId()))
                    && (taskId == null || taskId.equals(access.taskId()));
        }
    }
}

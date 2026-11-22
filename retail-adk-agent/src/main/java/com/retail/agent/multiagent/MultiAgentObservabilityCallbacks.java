package com.retail.agent.multiagent;

import com.google.adk.agents.Callbacks;
import com.google.adk.agents.CallbackContext;
import com.google.genai.types.Content;
import com.retail.agent.observability.AgentAuditLogger;

import java.util.Optional;

/**
 * Google ADK Agent lifecycle callbacks for tracking agent activation turns and execution boundaries.
 */
public class MultiAgentObservabilityCallbacks {

    /**
     * Triggered when an agent (coordinator or specialist) begins processing a turn.
     */
    public static Callbacks.BeforeAgentCallbackSync beforeAgent() {
        return (CallbackContext ctx) -> {
            if (ctx != null && ctx.invocationContext() != null) {
                var invCtx = ctx.invocationContext();
                String sessionId = (invCtx.session() != null) ? invCtx.session().id() : "unknown_session";
                String correlationId = invCtx.invocationId();
                String agentName = (invCtx.agent() != null) ? invCtx.agent().name() : "unknown_agent";

                AgentAuditLogger.logAgentStart(sessionId, correlationId, agentName);
            }
            return Optional.empty();
        };
    }

    /**
     * Triggered when an agent completes its turn.
     */
    public static Callbacks.AfterAgentCallbackSync afterAgent() {
        return (CallbackContext ctx) -> {
            if (ctx != null && ctx.invocationContext() != null) {
                var invCtx = ctx.invocationContext();
                String sessionId = (invCtx.session() != null) ? invCtx.session().id() : "unknown_session";
                String correlationId = invCtx.invocationId();
                String agentName = (invCtx.agent() != null) ? invCtx.agent().name() : "unknown_agent";

                AgentAuditLogger.logAgentEnd(sessionId, correlationId, agentName);
            }
            return Optional.empty();
        };
    }
}

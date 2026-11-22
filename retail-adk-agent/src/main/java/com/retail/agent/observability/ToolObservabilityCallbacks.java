package com.retail.agent.observability;

import com.google.adk.agents.Callbacks;
import com.google.adk.agents.InvocationContext;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;

import java.util.Map;
import java.util.Optional;

/**
 * Google ADK Tool Callbacks implementing non-intrusive structured observability,
 * latency measurement, and audit logging across both single-agent and multi-agent tool executions.
 */
public class ToolObservabilityCallbacks {

    private static final String START_TIME_PREFIX = "OBS_TOOL_START_TIME_";

    /**
     * Callback triggered immediately before an ADK tool executes.
     */
    public static Callbacks.BeforeToolCallbackSync beforeTool() {
        return (InvocationContext ctx, BaseTool tool, Map<String, Object> args, ToolContext toolCtx) -> {
            String sessionId = (ctx != null && ctx.session() != null) ? ctx.session().id() : "unknown_session";
            String correlationId = (ctx != null) ? ctx.invocationId() : "unknown_invocation";
            String agentName = (ctx != null && ctx.agent() != null) ? ctx.agent().name() : "retail_inventory_agent";
            String toolName = (tool != null) ? tool.name() : "unknown_tool";

            if (ctx != null && ctx.callbackContextData() != null) {
                ctx.callbackContextData().put(START_TIME_PREFIX + toolName, System.nanoTime());
            }

            if ("transfer_to_agent".equals(toolName) && args != null) {
                String targetAgent = String.valueOf(args.getOrDefault("agent_name", "unknown_target"));
                AgentAuditLogger.logAgentDelegation(sessionId, correlationId, agentName, targetAgent, "Task delegation");
            } else {
                AgentAuditLogger.logToolStart(sessionId, correlationId, agentName, toolName, args);
            }
            return Optional.empty();
        };
    }

    /**
     * Callback triggered immediately after an ADK tool completes successfully.
     */
    public static Callbacks.AfterToolCallbackSync afterTool() {
        return (InvocationContext ctx, BaseTool tool, Map<String, Object> args, ToolContext toolCtx, Object result) -> {
            String sessionId = (ctx != null && ctx.session() != null) ? ctx.session().id() : "unknown_session";
            String correlationId = (ctx != null) ? ctx.invocationId() : "unknown_invocation";
            String agentName = (ctx != null && ctx.agent() != null) ? ctx.agent().name() : "retail_inventory_agent";
            String toolName = (tool != null) ? tool.name() : "unknown_tool";

            double durationMs = 0.0;
            if (ctx != null && ctx.callbackContextData() != null) {
                Object startObj = ctx.callbackContextData().get(START_TIME_PREFIX + toolName);
                if (startObj instanceof Long startNano) {
                    durationMs = (System.nanoTime() - startNano) / 1_000_000.0;
                }
            }

            AgentAuditLogger.logToolSuccess(sessionId, correlationId, agentName, toolName, durationMs, result);
            return Optional.empty();
        };
    }

    /**
     * Callback triggered if an ADK tool throws an unhandled exception.
     */
    public static Callbacks.OnToolErrorCallbackSync onToolError() {
        return (InvocationContext ctx, BaseTool tool, Map<String, Object> args, ToolContext toolCtx, Exception err) -> {
            String sessionId = (ctx != null && ctx.session() != null) ? ctx.session().id() : "unknown_session";
            String correlationId = (ctx != null) ? ctx.invocationId() : "unknown_invocation";
            String agentName = (ctx != null && ctx.agent() != null) ? ctx.agent().name() : "retail_inventory_agent";
            String toolName = (tool != null) ? tool.name() : "unknown_tool";

            double durationMs = 0.0;
            if (ctx != null && ctx.callbackContextData() != null) {
                Object startObj = ctx.callbackContextData().get(START_TIME_PREFIX + toolName);
                if (startObj instanceof Long startNano) {
                    durationMs = (System.nanoTime() - startNano) / 1_000_000.0;
                }
            }

            AgentAuditLogger.logToolError(sessionId, correlationId, agentName, toolName, durationMs, err);
            return Optional.empty();
        };
    }
}

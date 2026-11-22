package com.retail.agent.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Enterprise structured audit logger for Google ADK AI Agent interactions.
 * <p>
 * Captures session correlation, agent delegation/transfer events, tool lifecycles,
 * latencies, and outcome statuses while strictly sanitizing outputs to prevent
 * credential or secret leakage.
 */
public class AgentAuditLogger {

    private static final Logger logger = LoggerFactory.getLogger(AgentAuditLogger.class);

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "key", "apikey", "api_key", "token", "secret", "password",
            "auth", "authorization", "credential", "credentials"
    );

    private static final int MAX_AUDIT_HISTORY = 500;
    private static final ConcurrentLinkedQueue<AuditEntry> AUDIT_BUFFER = new ConcurrentLinkedQueue<>();

    /**
     * Records an agent activation / turn start event.
     */
    public static void logAgentStart(String sessionId, String correlationId, String agentName) {
        AuditEntry entry = new AuditEntry(
                Instant.now(),
                safeString(sessionId, "UNKNOWN_SESSION"),
                safeString(correlationId, "UNKNOWN_INVOCATION"),
                safeString(agentName, "UNKNOWN_AGENT"),
                null,
                null,
                "AGENT_START",
                0.0,
                null,
                "STARTED",
                null
        );
        recordEntry(entry);
        logger.info("[AUDIT-AGENT-START] Session={} Correlation={} Agent={}",
                entry.sessionId(), entry.correlationId(), entry.agentName());
    }

    /**
     * Records an agent delegation / transfer event from coordinator to specialist.
     */
    public static void logAgentDelegation(String sessionId, String correlationId, String fromAgent, String toAgent, String reason) {
        AuditEntry entry = new AuditEntry(
                Instant.now(),
                safeString(sessionId, "UNKNOWN_SESSION"),
                safeString(correlationId, "UNKNOWN_INVOCATION"),
                safeString(fromAgent, "UNKNOWN_AGENT"),
                safeString(toAgent, "UNKNOWN_TARGET"),
                "transfer_to_agent",
                "AGENT_DELEGATION",
                0.0,
                reason != null ? Map.of("reason", reason) : Map.of(),
                "DELEGATED",
                null
        );
        recordEntry(entry);
        logger.info("[AUDIT-DELEGATION] Session={} Correlation={} FromAgent={} ToAgent={} Reason={}",
                entry.sessionId(), entry.correlationId(), entry.agentName(), entry.targetAgent(), reason);
    }

    /**
     * Records an agent turn completion event.
     */
    public static void logAgentEnd(String sessionId, String correlationId, String agentName) {
        AuditEntry entry = new AuditEntry(
                Instant.now(),
                safeString(sessionId, "UNKNOWN_SESSION"),
                safeString(correlationId, "UNKNOWN_INVOCATION"),
                safeString(agentName, "UNKNOWN_AGENT"),
                null,
                null,
                "AGENT_END",
                0.0,
                null,
                "COMPLETED",
                null
        );
        recordEntry(entry);
        logger.info("[AUDIT-AGENT-END] Session={} Correlation={} Agent={}",
                entry.sessionId(), entry.correlationId(), entry.agentName());
    }

    /**
     * Records a tool invocation start event with agent context.
     */
    public static void logToolStart(String sessionId, String correlationId, String agentName, String toolName, Map<String, Object> args) {
        Map<String, Object> sanitizedArgs = sanitizeMap(args);
        AuditEntry entry = new AuditEntry(
                Instant.now(),
                safeString(sessionId, "UNKNOWN_SESSION"),
                safeString(correlationId, "UNKNOWN_INVOCATION"),
                safeString(agentName, "retail_inventory_agent"),
                null,
                toolName,
                "START",
                0.0,
                sanitizedArgs,
                null,
                null
        );
        recordEntry(entry);
        logger.info("[AUDIT-START] Session={} Correlation={} Agent={} Tool={} Args={}",
                entry.sessionId(), entry.correlationId(), entry.agentName(), entry.toolName(), entry.args());
    }

    /**
     * Backward-compatible overload for single-agent tool start.
     */
    public static void logToolStart(String sessionId, String correlationId, String toolName, Map<String, Object> args) {
        logToolStart(sessionId, correlationId, "retail_inventory_agent", toolName, args);
    }

    /**
     * Records a successful tool completion event with execution duration and agent context.
     */
    public static void logToolSuccess(String sessionId, String correlationId, String agentName, String toolName, double durationMs, Object result) {
        String status = extractStatus(result);
        AuditEntry entry = new AuditEntry(
                Instant.now(),
                safeString(sessionId, "UNKNOWN_SESSION"),
                safeString(correlationId, "UNKNOWN_INVOCATION"),
                safeString(agentName, "retail_inventory_agent"),
                null,
                toolName,
                "SUCCESS",
                durationMs,
                null,
                status,
                null
        );
        recordEntry(entry);
        logger.info("[AUDIT-SUCCESS] Session={} Correlation={} Agent={} Tool={} Duration={}ms Status={}",
                entry.sessionId(), entry.correlationId(), entry.agentName(), entry.toolName(),
                String.format(Locale.ROOT, "%.2f", durationMs), status);
    }

    /**
     * Backward-compatible overload for single-agent tool success.
     */
    public static void logToolSuccess(String sessionId, String correlationId, String toolName, double durationMs, Object result) {
        logToolSuccess(sessionId, correlationId, "retail_inventory_agent", toolName, durationMs, result);
    }

    /**
     * Records a tool failure event with sanitized error details and agent context.
     */
    public static void logToolError(String sessionId, String correlationId, String agentName, String toolName, double durationMs, Throwable error) {
        String sanitizedMessage = sanitizeError(error);
        AuditEntry entry = new AuditEntry(
                Instant.now(),
                safeString(sessionId, "UNKNOWN_SESSION"),
                safeString(correlationId, "UNKNOWN_INVOCATION"),
                safeString(agentName, "retail_inventory_agent"),
                null,
                toolName,
                "FAILURE",
                durationMs,
                null,
                "ERROR",
                sanitizedMessage
        );
        recordEntry(entry);
        logger.error("[AUDIT-FAILURE] Session={} Correlation={} Agent={} Tool={} Duration={}ms Error={}",
                entry.sessionId(), entry.correlationId(), entry.agentName(), entry.toolName(),
                String.format(Locale.ROOT, "%.2f", durationMs), sanitizedMessage);
    }

    /**
     * Backward-compatible overload for single-agent tool error.
     */
    public static void logToolError(String sessionId, String correlationId, String toolName, double durationMs, Throwable error) {
        logToolError(sessionId, correlationId, "retail_inventory_agent", toolName, durationMs, error);
    }

    /**
     * Retrieves all recorded audit entries in thread-safe unmodifiable list.
     */
    public static List<AuditEntry> getAuditHistory() {
        return Collections.unmodifiableList(new ArrayList<>(AUDIT_BUFFER));
    }

    /**
     * Clears recorded audit buffer (primarily for test isolation).
     */
    public static void clearAuditHistory() {
        AUDIT_BUFFER.clear();
    }

    private static void recordEntry(AuditEntry entry) {
        AUDIT_BUFFER.add(entry);
        while (AUDIT_BUFFER.size() > MAX_AUDIT_HISTORY) {
            AUDIT_BUFFER.poll();
        }
    }

    /**
     * Sanitizes map keys to redact any potential passwords, API keys, or tokens.
     */
    public static Map<String, Object> sanitizeMap(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            if (isSensitiveKey(key)) {
                sanitized.put(key, "******");
            } else if (entry.getValue() instanceof Map<?, ?> nestedMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typedMap = (Map<String, Object>) nestedMap;
                sanitized.put(key, sanitizeMap(typedMap));
            } else {
                sanitized.put(key, entry.getValue());
            }
        }
        return Collections.unmodifiableMap(sanitized);
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.stream().anyMatch(lower::contains);
    }

    private static String extractStatus(Object result) {
        if (result == null) {
            return "COMPLETED_EMPTY";
        }
        if (result instanceof Map<?, ?> map && map.containsKey("status")) {
            return String.valueOf(map.get("status"));
        }
        return "COMPLETED";
    }

    private static String sanitizeError(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        String msg = error.getMessage();
        if (msg == null || msg.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return msg.replaceAll("(?i)(key|token|secret|password)[\\s:=]+[^\\s,;]+", "$1=******");
    }

    private static String safeString(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    /**
     * Immutable audit log record capturing multi-agent and tool execution telemetry.
     */
    public record AuditEntry(
            Instant timestamp,
            String sessionId,
            String correlationId,
            String agentName,
            String targetAgent,
            String toolName,
            String outcome, // "AGENT_START", "AGENT_DELEGATION", "AGENT_END", "START", "SUCCESS", "FAILURE"
            double durationMs,
            Map<String, Object> args,
            String resultStatus,
            String errorMessage
    ) {
    }
}

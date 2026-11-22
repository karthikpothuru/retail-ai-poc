package com.retail.agent.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentAuditLogger Structured Observability Tests")
class AgentAuditLoggerTest {

    @BeforeEach
    void setUp() {
        AgentAuditLogger.clearAuditHistory();
    }

    @Test
    @DisplayName("Should capture tool start and success with execution duration")
    void shouldCaptureToolStartAndSuccess() {
        String sessionId = "sess-123";
        String correlationId = "corr-456";
        String toolName = "lookup_inventory";
        Map<String, Object> args = Map.of("sku", "SKU-ELEC-1001", "warehouseId", "WH-CENTRAL-01");

        AgentAuditLogger.logToolStart(sessionId, correlationId, toolName, args);
        AgentAuditLogger.logToolSuccess(sessionId, correlationId, toolName, 12.5, Map.of("status", "FOUND"));

        List<AgentAuditLogger.AuditEntry> history = AgentAuditLogger.getAuditHistory();
        assertThat(history).hasSize(2);

        AgentAuditLogger.AuditEntry startEntry = history.get(0);
        assertThat(startEntry.outcome()).isEqualTo("START");
        assertThat(startEntry.sessionId()).isEqualTo(sessionId);
        assertThat(startEntry.correlationId()).isEqualTo(correlationId);
        assertThat(startEntry.toolName()).isEqualTo(toolName);
        assertThat(startEntry.args()).containsEntry("sku", "SKU-ELEC-1001");

        AgentAuditLogger.AuditEntry successEntry = history.get(1);
        assertThat(successEntry.outcome()).isEqualTo("SUCCESS");
        assertThat(successEntry.durationMs()).isEqualTo(12.5);
        assertThat(successEntry.resultStatus()).isEqualTo("FOUND");
    }

    @Test
    @DisplayName("Should capture tool failure and sanitize error messages")
    void shouldCaptureToolErrorAndSanitize() {
        String sessionId = "sess-999";
        String correlationId = "corr-888";
        String toolName = "calculate_replenishment";
        Exception ex = new RuntimeException("Connection failed for secret=super_secret_12345");

        AgentAuditLogger.logToolError(sessionId, correlationId, toolName, 25.0, ex);

        List<AgentAuditLogger.AuditEntry> history = AgentAuditLogger.getAuditHistory();
        assertThat(history).hasSize(1);

        AgentAuditLogger.AuditEntry errorEntry = history.get(0);
        assertThat(errorEntry.outcome()).isEqualTo("FAILURE");
        assertThat(errorEntry.errorMessage()).doesNotContain("super_secret_12345");
        assertThat(errorEntry.errorMessage()).contains("secret=******");
    }

    @Test
    @DisplayName("Should sanitize sensitive keys from parameter maps")
    void shouldSanitizeSensitiveKeys() {
        Map<String, Object> input = Map.of(
                "sku", "SKU-ELEC-1001",
                "warehouseId", "WH-CENTRAL-01",
                "api_key", "secret-token-value-xyz",
                "password", "p@ssword123",
                "nested", Map.of("credential", "sensitive_val", "safeParam", 42)
        );

        Map<String, Object> sanitized = AgentAuditLogger.sanitizeMap(input);

        assertThat(sanitized.get("sku")).isEqualTo("SKU-ELEC-1001");
        assertThat(sanitized.get("warehouseId")).isEqualTo("WH-CENTRAL-01");
        assertThat(sanitized.get("api_key")).isEqualTo("******");
        assertThat(sanitized.get("password")).isEqualTo("******");

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) sanitized.get("nested");
        assertThat(nested.get("credential")).isEqualTo("******");
        assertThat(nested.get("safeParam")).isEqualTo(42);
    }
}

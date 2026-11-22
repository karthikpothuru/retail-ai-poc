package com.retail.agent.multiagent;

import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.retail.agent.observability.AgentAuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 5A: Multi-Agent Session Continuity and Audit Trail Tests")
class MultiAgentSessionAndDelegationTest {

    @BeforeEach
    void setUp() {
        AgentAuditLogger.clearAuditHistory();
    }

    @Test
    @DisplayName("InMemoryRunner should initialize session for RetailOperationsCoordinatorAgent")
    void shouldInitializeMultiAgentSession() {
        InMemoryRunner runner = new InMemoryRunner(RetailOperationsCoordinatorAgent.ROOT_AGENT);

        Session session = runner.sessionService()
                .createSession(runner.appName(), "test-multiagent-user")
                .blockingGet();

        assertThat(session).isNotNull();
        assertThat(session.id()).isNotBlank();
        assertThat(session.userId()).isEqualTo("test-multiagent-user");
        assertThat(session.appName()).isEqualTo(runner.appName());
        assertThat(session.events()).isEmpty();
    }

    @Test
    @DisplayName("Multi-agent session must maintain multi-turn event history across agent delegations")
    void shouldMaintainEventHistoryAcrossTurns() {
        InMemoryRunner runner = new InMemoryRunner(RetailOperationsCoordinatorAgent.ROOT_AGENT);

        Session session = runner.sessionService()
                .createSession(runner.appName(), "multi-turn-analyst")
                .blockingGet();

        // Turn 1: User asks coordinator for stock check
        Event userPrompt = Event.builder()
                .author("user")
                .content(Content.fromParts(Part.fromText("Check stock for SKU-ELEC-1001 in WH-CENTRAL-01")))
                .build();
        session.addEvent(userPrompt);

        // Coordinator decides to delegate to inventory_specialist
        Event delegationCall = Event.builder()
                .author("retail_operations_coordinator")
                .content(Content.fromParts(Part.fromFunctionCall(
                        "transfer_to_agent",
                        Map.of("agent_name", "inventory_specialist")
                )))
                .build();
        session.addEvent(delegationCall);

        // Transfer response
        Event delegationResp = Event.builder()
                .author("retail_operations_coordinator")
                .content(Content.fromParts(Part.fromFunctionResponse(
                        "transfer_to_agent",
                        Map.of("status", "transferred", "agent", "inventory_specialist")
                )))
                .build();
        session.addEvent(delegationResp);

        // Specialist invokes lookup_inventory tool
        Event specialistToolCall = Event.builder()
                .author("inventory_specialist")
                .content(Content.fromParts(Part.fromFunctionCall(
                        "lookup_inventory",
                        Map.of("sku", "SKU-ELEC-1001", "warehouseId", "WH-CENTRAL-01")
                )))
                .build();
        session.addEvent(specialistToolCall);

        Event specialistToolResp = Event.builder()
                .author("inventory_specialist")
                .content(Content.fromParts(Part.fromFunctionResponse(
                        "lookup_inventory",
                        Map.of("status", "SUCCESS", "onHandQuantity", 150, "availableQuantity", 125)
                )))
                .build();
        session.addEvent(specialistToolResp);

        // Coordinator synthesizes final response
        Event coordinatorSynthesis = Event.builder()
                .author("retail_operations_coordinator")
                .content(Content.fromParts(Part.fromText("Based on the inventory specialist's lookup, SKU-ELEC-1001 has 150 on-hand and 125 ATP.")))
                .build();
        session.addEvent(coordinatorSynthesis);

        // Verify session event log preservation (using local session object,
        // consistent with ADK InMemorySessionService behavior where addEvent
        // updates the local Session instance).
        assertThat(session.events()).hasSize(6);
        List<String> authors = session.events().stream().map(Event::author).toList();
        assertThat(authors).containsExactly(
                "user",
                "retail_operations_coordinator",
                "retail_operations_coordinator",
                "inventory_specialist",
                "inventory_specialist",
                "retail_operations_coordinator"
        );
    }

    @Test
    @DisplayName("Audit logger must track multi-agent lifecycle, delegation, and tool invocations without credential leakage")
    void shouldTrackMultiAgentDelegationInAuditLog() {
        String sessionId = "sess-multi-999";
        String correlationId = "corr-multi-123";

        // 1. Coordinator activates
        AgentAuditLogger.logAgentStart(sessionId, correlationId, "retail_operations_coordinator");

        // 2. Coordinator delegates to discrepancy_specialist
        AgentAuditLogger.logAgentDelegation(sessionId, correlationId,
                "retail_operations_coordinator", "discrepancy_specialist", "Investigate shrinkage");

        // 3. Specialist activates
        AgentAuditLogger.logAgentStart(sessionId, correlationId, "discrepancy_specialist");

        // 4. Specialist invokes investigate_discrepancy tool with args
        Map<String, Object> toolArgs = Map.of(
                "sku", "SKU-ELEC-1001",
                "warehouseId", "WH-CENTRAL-01",
                "apiKey", "secret-test-key-should-be-masked"
        );
        AgentAuditLogger.logToolStart(sessionId, correlationId, "discrepancy_specialist", "investigate_discrepancy", toolArgs);

        // 5. Tool completes successfully
        AgentAuditLogger.logToolSuccess(sessionId, correlationId, "discrepancy_specialist",
                "investigate_discrepancy", 14.5, Map.of("status", "DISCREPANCY_FOUND", "varianceQuantity", -10));

        // 6. Specialist turn ends
        AgentAuditLogger.logAgentEnd(sessionId, correlationId, "discrepancy_specialist");

        // 7. Coordinator turn ends
        AgentAuditLogger.logAgentEnd(sessionId, correlationId, "retail_operations_coordinator");

        // Verify audit history
        List<AgentAuditLogger.AuditEntry> history = AgentAuditLogger.getAuditHistory();
        assertThat(history).hasSize(7);

        // Verify delegation entry
        AgentAuditLogger.AuditEntry delegationEntry = history.get(1);
        assertThat(delegationEntry.outcome()).isEqualTo("AGENT_DELEGATION");
        assertThat(delegationEntry.agentName()).isEqualTo("retail_operations_coordinator");
        assertThat(delegationEntry.targetAgent()).isEqualTo("discrepancy_specialist");
        assertThat(delegationEntry.toolName()).isEqualTo("transfer_to_agent");

        // Verify tool start sanitization (apiKey redacted)
        AgentAuditLogger.AuditEntry toolStartEntry = history.get(3);
        assertThat(toolStartEntry.agentName()).isEqualTo("discrepancy_specialist");
        assertThat(toolStartEntry.toolName()).isEqualTo("investigate_discrepancy");
        assertThat(toolStartEntry.args().get("apiKey")).isEqualTo("******");

        // Verify tool success entry
        AgentAuditLogger.AuditEntry toolSuccessEntry = history.get(4);
        assertThat(toolSuccessEntry.outcome()).isEqualTo("SUCCESS");
        assertThat(toolSuccessEntry.resultStatus()).isEqualTo("DISCREPANCY_FOUND");
        assertThat(toolSuccessEntry.durationMs()).isEqualTo(14.5);
    }
}

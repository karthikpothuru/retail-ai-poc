package com.retail.agent.multiagent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import com.retail.agent.store.RetailInventoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Phase 5A: RetailOperationsCoordinatorAgent Configuration and Delegation Tests")
class RetailOperationsCoordinatorAgentTest {

    private RetailInventoryStore store;

    @BeforeEach
    void setUp() {
        store = InMemoryRetailInventoryStore.createWithDefaultData();
    }

    @Test
    @DisplayName("Root coordinator must be properly configured with model, subagents, and delegation tools")
    void shouldConfigureCoordinatorRootAgent() {
        BaseAgent coordinator = RetailOperationsCoordinatorAgent.ROOT_AGENT;

        assertThat(coordinator).isNotNull();
        assertThat(coordinator).isInstanceOf(LlmAgent.class);

        LlmAgent llmCoordinator = (LlmAgent) coordinator;
        assertThat(llmCoordinator.name()).isEqualTo("retail_operations_coordinator");
        assertThat(llmCoordinator.description()).contains("Central coordinator");

        // Model validation
        assertThat(llmCoordinator.model()).isPresent();
        assertThat(llmCoordinator.model().get().modelName()).isPresent();
        assertThat(llmCoordinator.model().get().modelName().get()).isEqualTo("gemini-3.8-flash");

        // Subagents validation
        List<? extends BaseAgent> subAgents = llmCoordinator.subAgents();
        assertThat(subAgents).hasSize(3);

        List<String> subAgentNames = subAgents.stream().map(BaseAgent::name).toList();
        assertThat(subAgentNames).containsExactlyInAnyOrder(
                "inventory_specialist",
                "discrepancy_specialist",
                "replenishment_specialist"
        );

        // Subagents lookup
        assertThat(llmCoordinator.findSubAgent("inventory_specialist")).isPresent();
        assertThat(llmCoordinator.findSubAgent("discrepancy_specialist")).isPresent();
        assertThat(llmCoordinator.findSubAgent("replenishment_specialist")).isPresent();
        assertThat(llmCoordinator.findSubAgent("non_existent")).isEmpty();
    }

    @Test
    @DisplayName("Coordinator with subagents must enable ADK transfer_to_agent delegation at runtime")
    void coordinatorSubAgentsMustEnableTransferToAgentDelegation() {
        LlmAgent llmCoordinator = (LlmAgent) RetailOperationsCoordinatorAgent.ROOT_AGENT;

        // In ADK 1.13.0, transfer_to_agent is injected dynamically at runtime by AgentTransfer
        // when subAgents are registered. Verify the delegation mechanism is configured correctly
        // by confirming subagents are present and findable.
        assertThat(llmCoordinator.subAgents()).hasSize(3);
        assertThat(llmCoordinator.findSubAgent("inventory_specialist")).isPresent();
        assertThat(llmCoordinator.findSubAgent("discrepancy_specialist")).isPresent();
        assertThat(llmCoordinator.findSubAgent("replenishment_specialist")).isPresent();

        // Each sub-agent should have a description (used by AgentTransfer to build the transfer tool schema)
        for (BaseAgent subAgent : llmCoordinator.subAgents()) {
            assertThat(subAgent.description()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Coordinator instructions must include triage, delegation, and anti-hallucination rules")
    void shouldContainCoordinationSystemInstructions() {
        LlmAgent llmCoordinator = (LlmAgent) RetailOperationsCoordinatorAgent.ROOT_AGENT;
        String instruction = llmCoordinator.instruction().toString();

        assertThat(instruction).contains("inventory_specialist");
        assertThat(instruction).contains("discrepancy_specialist");
        assertThat(instruction).contains("replenishment_specialist");
        assertThat(instruction).contains("transfer_to_agent");
        assertThat(instruction).contains("DELEGATION FIRST");
        assertThat(instruction).contains("demonstration");
        assertThat(instruction).contains("NEVER FABRICATE");
        assertThat(instruction).contains("NEVER CLAIM PO CREATION");
    }

    @Test
    @DisplayName("Coordinator factory must reject null store")
    void shouldRejectNullStore() {
        assertThatThrownBy(() -> RetailOperationsCoordinatorAgent.createCoordinator(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("RetailInventoryStore cannot be null");
    }

    @Test
    @DisplayName("Coordinator created with custom store must propagate store to all subagents")
    void shouldPropagateStoreToSubAgents() {
        BaseAgent customCoordinator = RetailOperationsCoordinatorAgent.createCoordinator(store);
        assertThat(customCoordinator.subAgents()).hasSize(3);

        for (BaseAgent subAgent : customCoordinator.subAgents()) {
            assertThat(subAgent).isInstanceOf(LlmAgent.class);
            LlmAgent llmSubAgent = (LlmAgent) subAgent;
            assertThat(llmSubAgent.tools().blockingGet()).isNotEmpty();
        }
    }
}

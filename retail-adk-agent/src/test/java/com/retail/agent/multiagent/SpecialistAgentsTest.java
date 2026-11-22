package com.retail.agent.multiagent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseTool;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import com.retail.agent.store.RetailInventoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 5A: Specialist Agents Tool Ownership and Shared Store Tests")
class SpecialistAgentsTest {

    private RetailInventoryStore store;

    @BeforeEach
    void setUp() {
        store = InMemoryRetailInventoryStore.createWithDefaultData();
    }

    @Test
    @DisplayName("InventorySpecialistAgent should own lookup_inventory and summarize_stock with gemini-3.8-flash")
    void shouldConfigureInventorySpecialistAgent() {
        BaseAgent agent = InventorySpecialistAgent.create(store);

        assertThat(agent).isInstanceOf(LlmAgent.class);
        LlmAgent llmAgent = (LlmAgent) agent;

        assertThat(llmAgent.name()).isEqualTo("inventory_specialist");
        assertThat(llmAgent.description()).contains("stock balance");
        assertThat(llmAgent.model()).isPresent();
        assertThat(llmAgent.model().get().modelName()).isPresent();
        assertThat(llmAgent.model().get().modelName().get()).isEqualTo("gemini-3.8-flash");

        List<BaseTool> tools = llmAgent.tools().blockingGet();
        assertThat(tools).hasSize(2);
        List<String> toolNames = tools.stream().map(BaseTool::name).toList();
        assertThat(toolNames).containsExactlyInAnyOrder("lookup_inventory", "summarize_stock");

        String instruction = llmAgent.instruction().toString();
        assertThat(instruction).contains("demonstration");
        assertThat(instruction).contains("lookup_inventory");
        assertThat(instruction).contains("summarize_stock");
    }

    @Test
    @DisplayName("DiscrepancySpecialistAgent should own investigate_discrepancy with gemini-3.8-flash")
    void shouldConfigureDiscrepancySpecialistAgent() {
        BaseAgent agent = DiscrepancySpecialistAgent.create(store);

        assertThat(agent).isInstanceOf(LlmAgent.class);
        LlmAgent llmAgent = (LlmAgent) agent;

        assertThat(llmAgent.name()).isEqualTo("discrepancy_specialist");
        assertThat(llmAgent.description()).contains("cycle count");
        assertThat(llmAgent.model()).isPresent();
        assertThat(llmAgent.model().get().modelName()).isPresent();
        assertThat(llmAgent.model().get().modelName().get()).isEqualTo("gemini-3.8-flash");

        List<BaseTool> tools = llmAgent.tools().blockingGet();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("investigate_discrepancy");

        String instruction = llmAgent.instruction().toString();
        assertThat(instruction).contains("demonstration");
        assertThat(instruction).contains("investigate_discrepancy");
        assertThat(instruction).contains("NO SPECULATION");
    }

    @Test
    @DisplayName("ReplenishmentSpecialistAgent should own calculate_replenishment with gemini-3.8-flash")
    void shouldConfigureReplenishmentSpecialistAgent() {
        BaseAgent agent = ReplenishmentSpecialistAgent.create(store);

        assertThat(agent).isInstanceOf(LlmAgent.class);
        LlmAgent llmAgent = (LlmAgent) agent;

        assertThat(llmAgent.name()).isEqualTo("replenishment_specialist");
        assertThat(llmAgent.description()).contains("reorder point");
        assertThat(llmAgent.model()).isPresent();
        assertThat(llmAgent.model().get().modelName()).isPresent();
        assertThat(llmAgent.model().get().modelName().get()).isEqualTo("gemini-3.8-flash");

        List<BaseTool> tools = llmAgent.tools().blockingGet();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).name()).isEqualTo("calculate_replenishment");

        String instruction = llmAgent.instruction().toString();
        assertThat(instruction).contains("demonstration");
        assertThat(instruction).contains("calculate_replenishment");
        assertThat(instruction).contains("purchase order has been placed");
        assertThat(instruction).contains("ADVISORY ONLY");
    }

    @Test
    @DisplayName("All specialists bound to the same store must reflect shared data mutations")
    @SuppressWarnings("unchecked")
    void allSpecialistsMustReflectSharedStoreMutations() {
        BaseAgent inventoryAgent = InventorySpecialistAgent.create(store);
        BaseAgent discrepancyAgent = DiscrepancySpecialistAgent.create(store);
        BaseAgent replenishmentAgent = ReplenishmentSpecialistAgent.create(store);

        // Mutate stock in the shared store
        store.updateStock("SKU-ELEC-1001", "WH-CENTRAL-01", 999, 50);

        // Inventory tool check
        BaseTool lookupTool = ((LlmAgent) inventoryAgent).tools().blockingGet().stream()
                .filter(t -> t.name().equals("lookup_inventory"))
                .findFirst().orElseThrow();
        Map<String, Object> lookupResult = (Map<String, Object>) lookupTool.runAsync(Map.of(
                "sku", "SKU-ELEC-1001",
                "warehouseId", "WH-CENTRAL-01"
        ), null).blockingGet();
        assertThat(lookupResult.get("onHandQuantity")).isEqualTo(999);
        assertThat(lookupResult.get("availableQuantity")).isEqualTo(949);

        // Discrepancy tool check - uses historical cycle count records (not live stock).
        // The tool's status is "FOUND" when audit records exist; variance is per-record, not vs live stock.
        BaseTool discTool = ((LlmAgent) discrepancyAgent).tools().blockingGet().get(0);
        Map<String, Object> discResult = (Map<String, Object>) discTool.runAsync(Map.of(
                "sku", "SKU-ELEC-1001",
                "warehouseId", "WH-CENTRAL-01"
        ), null).blockingGet();
        assertThat(discResult.get("status")).isEqualTo("FOUND");
        // Verify audit data is present (store mutation does not change historical cycle count records)
        assertThat(discResult.get("totalAuditCount")).isNotNull();
        assertThat((int) discResult.get("totalAuditCount")).isGreaterThan(0);

        // Replenishment tool check (available is 949 > ROP 60 -> no reorder needed)
        BaseTool repTool = ((LlmAgent) replenishmentAgent).tools().blockingGet().get(0);
        Map<String, Object> repResult = (Map<String, Object>) repTool.runAsync(Map.of(
                "sku", "SKU-ELEC-1001",
                "warehouseId", "WH-CENTRAL-01"
        ), null).blockingGet();
        assertThat(repResult.get("status")).isEqualTo("SUCCESS");
        assertThat(repResult.get("availableQuantity")).isEqualTo(949);
        assertThat(repResult.get("reorderNeeded")).isEqualTo(false);
        assertThat(repResult.get("urgencyLevel")).isEqualTo("NORMAL");
    }
}

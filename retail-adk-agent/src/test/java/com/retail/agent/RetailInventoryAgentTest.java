package com.retail.agent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.Instruction;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseTool;
import com.retail.agent.model.ProductSku;
import com.retail.agent.model.ReorderPolicy;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RetailInventoryAgent Configuration and Tool Binding Tests")
class RetailInventoryAgentTest {

    @Test
    @DisplayName("Should correctly configure RetailInventoryAgent metadata and Gemini model")
    void shouldConfigureAgentMetadata() {
        BaseAgent agent = RetailInventoryAgent.ROOT_AGENT;

        assertThat(agent).isNotNull();
        assertThat(agent).isInstanceOf(LlmAgent.class);

        LlmAgent llmAgent = (LlmAgent) agent;
        assertThat(llmAgent.name()).isEqualTo(RetailInventoryAgent.AGENT_NAME);
        assertThat(llmAgent.description()).isEqualTo(RetailInventoryAgent.AGENT_DESCRIPTION);

        // Verify preserved Gemini model
        assertThat(llmAgent.model()).isPresent();
        assertThat(llmAgent.model().get().modelName()).isPresent();
        assertThat(llmAgent.model().get().modelName().get()).isEqualTo(RetailInventoryAgent.MODEL_NAME);
        assertThat(llmAgent.model().get().modelName().get()).isEqualTo("gemini-3.8-flash");
    }

    @Test
    @DisplayName("Should include all required enterprise guardrails in system instruction")
    void shouldContainEnterpriseSystemInstructions() {
        LlmAgent llmAgent = (LlmAgent) RetailInventoryAgent.ROOT_AGENT;
        String instruction = llmAgent.instruction().toString();

        assertThat(instruction).contains("demonstration dataset");
        assertThat(instruction).contains("lookup_inventory");
        assertThat(instruction).contains("investigate_discrepancy");
        assertThat(instruction).contains("calculate_replenishment");
        assertThat(instruction).contains("summarize_stock");
        assertThat(instruction).contains("NEVER INVENT DATA");
        assertThat(instruction).contains("REQUEST MISSING INFORMATION");
        assertThat(instruction).contains("FACTUAL EVIDENCE VS ASSUMPTIONS");
        assertThat(instruction).contains("purchase order has been placed");
        assertThat(instruction).contains("TOOL-FIRST PRINCIPLE");
    }

    @Test
    @DisplayName("Should register all four Google ADK tools on the agent")
    void shouldRegisterAllFourAdkTools() {
        LlmAgent llmAgent = (LlmAgent) RetailInventoryAgent.ROOT_AGENT;
        List<BaseTool> tools = llmAgent.tools().blockingGet();

        assertThat(tools).hasSize(4);

        List<String> toolNames = tools.stream().map(BaseTool::name).toList();
        assertThat(toolNames).containsExactlyInAnyOrder(
                "lookup_inventory",
                "investigate_discrepancy",
                "calculate_replenishment",
                "summarize_stock"
        );

        // Verify all tools have generated FunctionDeclarations
        for (BaseTool tool : tools) {
            assertThat(tool.declaration()).isPresent();
            assertThat(tool.declaration().get().name()).isPresent();
            assertThat(tool.description()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Should verify shared store usage across registered tools")
    void shouldVerifySharedStoreUsageAcrossTools() {
        // Create an isolated custom store
        InMemoryRetailInventoryStore customStore = InMemoryRetailInventoryStore.createEmpty();

        ProductSku testSku = new ProductSku(
                "SKU-TEST-9999",
                "Enterprise Test Widget",
                "Testing",
                new BigDecimal("49.99"),
                "999999999999",
                "EACH"
        );
        customStore.registerProduct(testSku);
        customStore.updateStock("SKU-TEST-9999", "WH-TEST-01", 100, 20);
        customStore.setReorderPolicy(new ReorderPolicy("SKU-TEST-9999", "WH-TEST-01", 40, 15, 50, 7));

        // Create agent bound to the custom store
        BaseAgent customAgent = RetailInventoryAgent.createAgent(customStore);
        LlmAgent llmAgent = (LlmAgent) customAgent;
        List<BaseTool> tools = llmAgent.tools().blockingGet();

        BaseTool lookupTool = tools.stream()
                .filter(t -> t.name().equals("lookup_inventory"))
                .findFirst()
                .orElseThrow();

        BaseTool summaryTool = tools.stream()
                .filter(t -> t.name().equals("summarize_stock"))
                .findFirst()
                .orElseThrow();

        // 1. Initial lookup through agent's lookup tool
        Map<String, Object> lookupResult1 = lookupTool.runAsync(
                Map.of("sku", "SKU-TEST-9999", "warehouseId", "WH-TEST-01"),
                null
        ).blockingGet();
        assertThat(lookupResult1.get("status")).isEqualTo("FOUND");
        assertThat(lookupResult1.get("availableQuantity")).isEqualTo(80);

        // 2. Mutate stock in the shared store
        customStore.updateStock("SKU-TEST-9999", "WH-TEST-01", 200, 50);

        // 3. Verify lookup tool immediately observes the mutation (shared store)
        Map<String, Object> lookupResult2 = lookupTool.runAsync(
                Map.of("sku", "SKU-TEST-9999", "warehouseId", "WH-TEST-01"),
                null
        ).blockingGet();
        assertThat(lookupResult2.get("availableQuantity")).isEqualTo(150);

        // 4. Verify summary tool simultaneously observes the exact same mutated state
        Map<String, Object> summaryResult = summaryTool.runAsync(
                Map.of("warehouseId", "WH-TEST-01", "category", "Testing"),
                null
        ).blockingGet();
        assertThat(summaryResult.get("status")).isEqualTo("SUCCESS");
        assertThat(summaryResult.get("totalAvailableUnits")).isEqualTo(150);
        assertThat(summaryResult.get("totalOnHandUnits")).isEqualTo(200);
    }
}

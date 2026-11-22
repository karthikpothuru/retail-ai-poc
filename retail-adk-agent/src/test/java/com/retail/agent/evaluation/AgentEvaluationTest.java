package com.retail.agent.evaluation;

import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.BaseTool;
import com.retail.agent.RetailInventoryAgent;
import com.retail.agent.model.ProductSku;
import com.retail.agent.model.StockLevel;
import com.retail.agent.store.RetailInventoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Automated AI Agent Evaluation Scenarios (Deterministic Contracts & Governance)")
class AgentEvaluationTest {

    private LlmAgent agent;
    private RetailInventoryStore store;
    private Map<String, BaseTool> toolRegistry;

    @BeforeEach
    void setUp() {
        agent = (LlmAgent) RetailInventoryAgent.ROOT_AGENT;
        store = RetailInventoryAgent.SHARED_STORE;

        List<BaseTool> tools = agent.tools().blockingGet();
        toolRegistry = Map.of(
                "lookup_inventory", findTool(tools, "lookup_inventory"),
                "investigate_discrepancy", findTool(tools, "investigate_discrepancy"),
                "calculate_replenishment", findTool(tools, "calculate_replenishment"),
                "summarize_stock", findTool(tools, "summarize_stock")
        );
    }

    private BaseTool findTool(List<BaseTool> tools, String name) {
        return tools.stream()
                .filter(t -> t.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing expected tool: " + name));
    }

    @Test
    @DisplayName("Scenario 1: Tool Selection & Argument Contract Validation")
    void shouldVerifyToolSelectionAndArgumentContracts() {
        BaseTool lookupTool = toolRegistry.get("lookup_inventory");
        assertThat(lookupTool).isNotNull();
        assertThat(lookupTool.name()).isEqualTo("lookup_inventory");

        var declaration = lookupTool.declaration().orElseThrow();
        assertThat(declaration.name()).contains("lookup_inventory");

        // Execute with expected argument structure
        Map<String, Object> response = lookupTool.runAsync(
                Map.of("sku", "SKU-ELEC-1001", "warehouseId", "WH-CENTRAL-01"),
                null
        ).blockingGet();

        assertThat(response.get("status")).isEqualTo("FOUND");
        assertThat(response.get("sku")).isEqualTo("SKU-ELEC-1001");
        assertThat(response.get("warehouseId")).isEqualTo("WH-CENTRAL-01");
    }

    @Test
    @DisplayName("Scenario 2: Inventory Calculation Accuracy & Non-Fabrication Invariant")
    void shouldVerifyCalculationAccuracyAcrossAllStockRecords() {
        BaseTool lookupTool = toolRegistry.get("lookup_inventory");

        // Verify every SKU across all warehouses: onHand - reserved MUST equal availableQuantity
        for (ProductSku product : store.getAllProducts()) {
            for (String warehouseId : store.getAvailableWarehouses()) {
                Map<String, Object> result = lookupTool.runAsync(
                        Map.of("sku", product.sku(), "warehouseId", warehouseId),
                        null
                ).blockingGet();

                if ("FOUND".equals(result.get("status"))) {
                    int onHand = ((Number) result.get("onHandQuantity")).intValue();
                    int reserved = ((Number) result.get("reservedQuantity")).intValue();
                    int available = ((Number) result.get("availableQuantity")).intValue();

                    // Strict arithmetic accuracy check
                    assertThat(available)
                            .as("Available stock calculation must exactly equal onHand - reserved for %s at %s", product.sku(), warehouseId)
                            .isEqualTo(onHand - reserved);

                    // Cross-check against authoritative store
                    StockLevel authoritativeStock = store.getStockLevel(product.sku(), warehouseId).orElseThrow();
                    assertThat(onHand).isEqualTo(authoritativeStock.onHandQuantity());
                    assertThat(reserved).isEqualTo(authoritativeStock.reservedQuantity());
                }
            }
        }
    }

    @Test
    @DisplayName("Scenario 3: Discrepancy Calculation & Factual Grounding Evaluation")
    void shouldVerifyDiscrepancyVarianceAndFactualGrounding() {
        BaseTool discrepancyTool = toolRegistry.get("investigate_discrepancy");

        // Evaluate known shrinkage case: SKU-ELEC-1002 at WH-EAST-02
        Map<String, Object> result = discrepancyTool.runAsync(
                Map.of("sku", "SKU-ELEC-1002", "warehouseId", "WH-EAST-02"),
                null
        ).blockingGet();

        assertThat(result.get("status")).isEqualTo("FOUND");
        int netVariance = ((Number) result.get("netVarianceUnits")).intValue();
        int shrinkageCount = ((Number) result.get("shrinkageAuditCount")).intValue();

        assertThat(netVariance).isEqualTo(-7);
        assertThat(shrinkageCount).isEqualTo(1);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> audits = (List<Map<String, Object>>) result.get("auditRecords");
        assertThat(audits).isNotEmpty();
        Map<String, Object> audit = audits.get(0);

        // Grounding check: verified facts present
        assertThat(audit.get("classification")).isEqualTo("SHRINKAGE");
        assertThat(audit.get("variance")).isEqualTo(-7);
        assertThat(audit.get("physicalCount")).isEqualTo(95);
        assertThat(audit.get("systemExpectedQuantity")).isEqualTo(102);

        // Documented auditor evidence preserved
        String notes = String.valueOf(audit.get("documentedNotes"));
        assertThat(notes).contains("Master carton was opened");
    }

    @Test
    @DisplayName("Scenario 4: Replenishment Threshold Evaluation & No-False-PO Claim Invariant")
    void shouldVerifyReplenishmentCalculationAndNonPoCreationRule() {
        BaseTool replenishmentTool = toolRegistry.get("calculate_replenishment");

        // Critical safety stock breach case: SKU-ELEC-1001 at WH-WEST-03
        Map<String, Object> result = replenishmentTool.runAsync(
                Map.of("sku", "SKU-ELEC-1001", "warehouseId", "WH-WEST-03"),
                null
        ).blockingGet();

        assertThat(result.get("status")).isEqualTo("SUCCESS");
        assertThat(result.get("urgencyLevel")).isEqualTo("CRITICAL");
        assertThat(result.get("safetyStockBreached")).isEqualTo(true);
        assertThat(result.get("reorderNeeded")).isEqualTo(true);

        int available = ((Number) result.get("availableQuantity")).intValue();
        int safetyStock = ((Number) result.get("safetyStock")).intValue();
        assertThat(available).isLessThan(safetyStock);

        // Governance Invariant: Must explicitly disclaim purchase order placement
        String poDisclaimer = String.valueOf(result.get("orderStatusNote"));
        assertThat(poDisclaimer)
                .contains("CALCULATED_RECOMMENDATION_ONLY")
                .contains("does NOT represent an approved or confirmed purchase order");
    }

    @Test
    @DisplayName("Scenario 5: Missing Parameter Evaluation Across All Tools")
    void shouldHandleMissingParametersAcrossTools() {
        for (String toolName : List.of("lookup_inventory", "investigate_discrepancy", "calculate_replenishment")) {
            BaseTool tool = toolRegistry.get(toolName);

            // Null/Missing SKU in arguments
            Map<String, Object> res1 = tool.runAsync(Map.of("warehouseId", "WH-CENTRAL-01"), null).blockingGet();
            assertThat(res1.get("status"))
                    .as("Tool %s must report error or INVALID_INPUT when SKU argument is omitted", toolName)
                    .isIn("INVALID_INPUT", "error");

            // Empty/Blank SKU
            Map<String, Object> res2 = tool.runAsync(Map.of("sku", "   ", "warehouseId", "WH-CENTRAL-01"), null).blockingGet();
            assertThat(res2.get("status"))
                    .as("Tool %s must reject blank SKU", toolName)
                    .isIn("INVALID_INPUT", "error");

            // Null/Missing Warehouse in arguments
            Map<String, Object> res3 = tool.runAsync(Map.of("sku", "SKU-ELEC-1001"), null).blockingGet();
            assertThat(res3.get("status"))
                    .as("Tool %s must report error or INVALID_INPUT when warehouse argument is omitted", toolName)
                    .isIn("INVALID_INPUT", "error");
        }
    }

    @Test
    @DisplayName("Scenario 6: Unknown SKU and Warehouse Handling (No Hallucination)")
    void shouldHandleUnknownSkuAndWarehouseWithoutHallucination() {
        BaseTool lookupTool = toolRegistry.get("lookup_inventory");

        // Unknown SKU
        Map<String, Object> skuNotFound = lookupTool.runAsync(
                Map.of("sku", "SKU-UNKNOWN-FAKETV", "warehouseId", "WH-CENTRAL-01"),
                null
        ).blockingGet();
        assertThat(skuNotFound.get("status")).isEqualTo("SKU_NOT_FOUND");
        assertThat(skuNotFound.get("availableQuantity")).isEqualTo(0);

        // Unknown Warehouse
        Map<String, Object> whNotFound = lookupTool.runAsync(
                Map.of("sku", "SKU-ELEC-1001", "warehouseId", "WH-ATLANTIS-99"),
                null
        ).blockingGet();
        assertThat(whNotFound.get("status")).isEqualTo("WAREHOUSE_NOT_FOUND");
        assertThat(whNotFound.get("availableQuantity")).isEqualTo(0);
    }

    @Test
    @DisplayName("Scenario 7: Stock Summary Determinism & Classification Partitioning")
    void shouldVerifyStockSummaryPartitioning() {
        BaseTool summaryTool = toolRegistry.get("summarize_stock");

        Map<String, Object> summary = summaryTool.runAsync(Map.of(), null).blockingGet();
        assertThat(summary.get("status")).isEqualTo("SUCCESS");

        int totalEvaluated = ((Number) summary.get("totalItemsEvaluated")).intValue();
        int criticalCount = ((Number) summary.get("criticalStockCount")).intValue();
        int lowCount = ((Number) summary.get("lowStockCount")).intValue();
        int healthyCount = ((Number) summary.get("healthyStockCount")).intValue();

        // Exact partitioning check
        assertThat(criticalCount + lowCount + healthyCount).isEqualTo(totalEvaluated);

        int totalOnHand = ((Number) summary.get("totalOnHandUnits")).intValue();
        int totalReserved = ((Number) summary.get("totalReservedUnits")).intValue();
        int totalAvailable = ((Number) summary.get("totalAvailableUnits")).intValue();

        // System-wide ATP calculation accuracy
        assertThat(totalOnHand - totalReserved).isEqualTo(totalAvailable);
    }
}

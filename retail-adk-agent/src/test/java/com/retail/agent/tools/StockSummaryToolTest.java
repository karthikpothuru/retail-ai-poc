package com.retail.agent.tools;

import com.google.adk.tools.FunctionTool;
import com.google.genai.types.FunctionDeclaration;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StockSummaryTool Tests")
class StockSummaryToolTest {

    private InMemoryRetailInventoryStore store;
    private StockSummaryTool tool;

    @BeforeEach
    void setUp() {
        store = InMemoryRetailInventoryStore.createWithDefaultData();
        tool = new StockSummaryTool(store);
    }

    @Test
    @DisplayName("Should summarize inventory across all warehouses and categories")
    void shouldSummarizeAllStock() {
        StockSummaryTool.StockSummaryResult result = tool.summarizeStock(null, null);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.warehouseFilter()).isEqualTo("ALL");
        assertThat(result.categoryFilter()).isEqualTo("ALL");
        assertThat(result.totalItemsEvaluated()).isEqualTo(24); // 8 SKUs * 3 warehouses
        assertThat(result.totalOnHandUnits()).isPositive();
        assertThat(result.totalAvailableUnits()).isPositive();

        // Verify counts
        assertThat(result.criticalStockCount()).isGreaterThan(0);
        assertThat(result.lowStockCount()).isGreaterThan(0);
        assertThat(result.healthyStockCount()).isGreaterThan(0);
        assertThat(result.criticalStockCount() + result.lowStockCount() + result.healthyStockCount())
                .isEqualTo(result.totalItemsEvaluated());
    }

    @Test
    @DisplayName("Should summarize inventory filtered by specific warehouse")
    void shouldSummarizeByWarehouse() {
        StockSummaryTool.StockSummaryResult result = tool.summarizeStock("WH-WEST-03", null);

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.warehouseFilter()).isEqualTo("WH-WEST-03");
        assertThat(result.totalItemsEvaluated()).isEqualTo(8);

        // Verify all returned items belong to WH-WEST-03
        assertThat(result.items()).allMatch(item -> item.warehouseId().equals("WH-WEST-03"));

        // Verify that depleted items in West DC are marked critical
        assertThat(result.criticalStockCount()).isGreaterThan(0);
        assertThat(result.items()).anyMatch(i -> i.stockHealth().equals("CRITICAL_STOCK"));
    }

    @Test
    @DisplayName("Should summarize inventory filtered by product category")
    void shouldSummarizeByCategory() {
        StockSummaryTool.StockSummaryResult result = tool.summarizeStock(null, "Consumer Electronics");

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.totalItemsEvaluated()).isEqualTo(9); // 3 electronic SKUs * 3 warehouses
        assertThat(result.items()).allMatch(i -> i.category().equalsIgnoreCase("Consumer Electronics"));
    }

    @Test
    @DisplayName("Should return INVALID_INPUT when filtering by unknown warehouse")
    void shouldHandleUnknownWarehouse() {
        StockSummaryTool.StockSummaryResult result = tool.summarizeStock("WH-INVALID-09", null);

        assertThat(result.status()).isEqualTo("INVALID_INPUT");
        assertThat(result.message()).contains("not recognized");
        assertThat(result.totalItemsEvaluated()).isZero();
    }

    @Test
    @DisplayName("Should return NO_DATA when category filter matches no products")
    void shouldHandleNoMatchingCategory() {
        StockSummaryTool.StockSummaryResult result = tool.summarizeStock(null, "Automotive Parts");

        assertThat(result.status()).isEqualTo("NO_DATA");
        assertThat(result.totalItemsEvaluated()).isZero();
    }

    @Test
    @DisplayName("Should expose Google ADK FunctionTool and run asynchronously")
    void shouldExecuteViaAdkFunctionTool() {
        FunctionTool functionTool = tool.asFunctionTool();
        assertThat(functionTool.name()).isEqualTo("summarize_stock");

        FunctionDeclaration decl = functionTool.declaration().orElseThrow();
        assertThat(decl.name()).contains("summarize_stock");

        Map<String, Object> args = Map.of(
                "warehouseId", "WH-EAST-02",
                "category", "Apparel & Footwear"
        );

        Map<String, Object> response = functionTool.runAsync(args, null).blockingGet();
        assertThat(response).isNotNull();
        assertThat(response.get("status")).isEqualTo("SUCCESS");
        assertThat(response.get("warehouseFilter")).isEqualTo("WH-EAST-02");
    }
}

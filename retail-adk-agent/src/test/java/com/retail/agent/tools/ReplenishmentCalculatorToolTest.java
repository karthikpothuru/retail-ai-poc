package com.retail.agent.tools;

import com.google.adk.tools.FunctionTool;
import com.google.genai.types.FunctionDeclaration;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReplenishmentCalculatorTool Tests")
class ReplenishmentCalculatorToolTest {

    private InMemoryRetailInventoryStore store;
    private ReplenishmentCalculatorTool tool;

    @BeforeEach
    void setUp() {
        store = InMemoryRetailInventoryStore.createWithDefaultData();
        tool = new ReplenishmentCalculatorTool(store);
    }

    @Test
    @DisplayName("Should evaluate healthy stock with no replenishment needed")
    void shouldEvaluateHealthyStock() {
        // SKU-ELEC-1001 @ Central: On-hand 140, reserved 25 -> available 115. ROP=40, Safety=15
        ReplenishmentCalculatorTool.ReplenishmentRecommendationResult result =
                tool.calculateReplenishment("SKU-ELEC-1001", "WH-CENTRAL-01");

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.sku()).isEqualTo("SKU-ELEC-1001");
        assertThat(result.availableQuantity()).isEqualTo(115);
        assertThat(result.reorderPoint()).isEqualTo(40);
        assertThat(result.safetyStock()).isEqualTo(15);
        assertThat(result.reorderNeeded()).isFalse();
        assertThat(result.safetyStockBreached()).isFalse();
        assertThat(result.urgencyLevel()).isEqualTo("NORMAL");
        assertThat(result.recommendedOrderQuantity()).isZero();
        assertThat(result.orderStatusNote()).contains("does NOT represent an approved or confirmed purchase order");
    }

    @Test
    @DisplayName("Should evaluate reorder needed when available stock drops to ROP")
    void shouldEvaluateReorderNeeded() {
        // SKU-ELEC-1001 @ East: On-hand 32, reserved 12 -> available 20. ROP=25, Safety=10, ReorderQty=50
        ReplenishmentCalculatorTool.ReplenishmentRecommendationResult result =
                tool.calculateReplenishment("SKU-ELEC-1001", "WH-EAST-02");

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.availableQuantity()).isEqualTo(20);
        assertThat(result.reorderNeeded()).isTrue();
        assertThat(result.safetyStockBreached()).isFalse();
        assertThat(result.urgencyLevel()).isEqualTo("MEDIUM");
        assertThat(result.recommendedOrderQuantity()).isGreaterThanOrEqualTo(50);
        assertThat(result.leadTimeDays()).isEqualTo(10);
    }

    @Test
    @DisplayName("Should evaluate critical urgency when safety stock is breached")
    void shouldEvaluateSafetyStockBreach() {
        // SKU-ELEC-1001 @ West: On-hand 8, reserved 5 -> available 3. ROP=20, Safety=8, ReorderQty=50
        ReplenishmentCalculatorTool.ReplenishmentRecommendationResult result =
                tool.calculateReplenishment("SKU-ELEC-1001", "WH-WEST-03");

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.availableQuantity()).isEqualTo(3);
        assertThat(result.reorderNeeded()).isTrue();
        assertThat(result.safetyStockBreached()).isTrue();
        assertThat(result.urgencyLevel()).isEqualTo("CRITICAL");
        assertThat(result.recommendedOrderQuantity()).isGreaterThan(50);
        assertThat(result.orderStatusNote()).contains("CALCULATED_RECOMMENDATION_ONLY");
    }

    @Test
    @DisplayName("Should return SKU_NOT_FOUND for unknown SKU")
    void shouldHandleUnknownSku() {
        ReplenishmentCalculatorTool.ReplenishmentRecommendationResult result =
                tool.calculateReplenishment("SKU-UNKNOWN-99", "WH-CENTRAL-01");

        assertThat(result.status()).isEqualTo("SKU_NOT_FOUND");
        assertThat(result.urgencyLevel()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("Should return WAREHOUSE_NOT_FOUND for unknown warehouse")
    void shouldHandleUnknownWarehouse() {
        ReplenishmentCalculatorTool.ReplenishmentRecommendationResult result =
                tool.calculateReplenishment("SKU-ELEC-1001", "WH-NOWHERE");

        assertThat(result.status()).isEqualTo("WAREHOUSE_NOT_FOUND");
    }

    @Test
    @DisplayName("Should return INVALID_INPUT for blank or null inputs")
    void shouldRejectInvalidInputs() {
        assertThat(tool.calculateReplenishment(null, "WH-01").status()).isEqualTo("INVALID_INPUT");
        assertThat(tool.calculateReplenishment("SKU-1", null).status()).isEqualTo("INVALID_INPUT");
        assertThat(tool.calculateReplenishment(" ", " ").status()).isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("Should expose Google ADK FunctionTool and run asynchronously")
    void shouldExecuteViaAdkFunctionTool() {
        FunctionTool functionTool = tool.asFunctionTool();
        assertThat(functionTool.name()).isEqualTo("calculate_replenishment");

        FunctionDeclaration decl = functionTool.declaration().orElseThrow();
        assertThat(decl.name()).contains("calculate_replenishment");

        Map<String, Object> args = Map.of(
                "sku", "SKU-ELEC-1001",
                "warehouseId", "WH-WEST-03"
        );

        Map<String, Object> response = functionTool.runAsync(args, null).blockingGet();
        assertThat(response).isNotNull();
        assertThat(response.get("status")).isEqualTo("SUCCESS");
        assertThat(response.get("urgencyLevel")).isEqualTo("CRITICAL");
        assertThat(response.get("orderStatusNote")).isNotNull();
    }
}

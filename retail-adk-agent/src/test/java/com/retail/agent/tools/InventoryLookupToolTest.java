package com.retail.agent.tools;

import com.google.adk.tools.FunctionTool;
import com.google.genai.types.FunctionDeclaration;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InventoryLookupTool Tests")
class InventoryLookupToolTest {

    private InMemoryRetailInventoryStore store;
    private InventoryLookupTool tool;

    @BeforeEach
    void setUp() {
        store = InMemoryRetailInventoryStore.createWithDefaultData();
        tool = new InventoryLookupTool(store);
    }

    @Test
    @DisplayName("Should successfully retrieve inventory for valid SKU and warehouse")
    void shouldLookupInventorySuccessfully() {
        // Central DC: On-hand 140, reserved 25 -> available 115
        InventoryLookupTool.InventoryLookupResult result =
                tool.lookupInventory("SKU-ELEC-1001", "WH-CENTRAL-01");

        assertThat(result.status()).isEqualTo("FOUND");
        assertThat(result.sku()).isEqualTo("SKU-ELEC-1001");
        assertThat(result.productName()).isEqualTo("OmniView 55-Inch 4K OLED Smart TV");
        assertThat(result.category()).isEqualTo("Consumer Electronics");
        assertThat(result.warehouseId()).isEqualTo("WH-CENTRAL-01");
        assertThat(result.onHandQuantity()).isEqualTo(140);
        assertThat(result.reservedQuantity()).isEqualTo(25);
        assertThat(result.availableQuantity()).isEqualTo(115);
        assertThat(result.message()).contains("Successfully retrieved");
    }

    @Test
    @DisplayName("Should return SKU_NOT_FOUND when querying unknown SKU")
    void shouldHandleUnknownSku() {
        InventoryLookupTool.InventoryLookupResult result =
                tool.lookupInventory("SKU-UNKNOWN-9999", "WH-CENTRAL-01");

        assertThat(result.status()).isEqualTo("SKU_NOT_FOUND");
        assertThat(result.message()).contains("not found in catalog");
        assertThat(result.availableQuantity()).isZero();
    }

    @Test
    @DisplayName("Should return WAREHOUSE_NOT_FOUND when querying unknown warehouse")
    void shouldHandleUnknownWarehouse() {
        InventoryLookupTool.InventoryLookupResult result =
                tool.lookupInventory("SKU-ELEC-1001", "WH-NONEXISTENT");

        assertThat(result.status()).isEqualTo("WAREHOUSE_NOT_FOUND");
        assertThat(result.message()).contains("Warehouse 'WH-NONEXISTENT' not recognized");
    }

    @Test
    @DisplayName("Should return INVALID_INPUT when SKU or warehouseId is blank or null")
    void shouldRejectInvalidInputs() {
        InventoryLookupTool.InventoryLookupResult nullSku = tool.lookupInventory(null, "WH-CENTRAL-01");
        assertThat(nullSku.status()).isEqualTo("INVALID_INPUT");

        InventoryLookupTool.InventoryLookupResult blankSku = tool.lookupInventory("   ", "WH-CENTRAL-01");
        assertThat(blankSku.status()).isEqualTo("INVALID_INPUT");

        InventoryLookupTool.InventoryLookupResult nullWh = tool.lookupInventory("SKU-ELEC-1001", null);
        assertThat(nullWh.status()).isEqualTo("INVALID_INPUT");

        InventoryLookupTool.InventoryLookupResult blankWh = tool.lookupInventory("SKU-ELEC-1001", "  ");
        assertThat(blankWh.status()).isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("Should correctly generate Google ADK FunctionTool and declaration schema")
    void shouldExposeValidAdkFunctionTool() {
        FunctionTool functionTool = tool.asFunctionTool();

        assertThat(functionTool).isNotNull();
        assertThat(functionTool.name()).isEqualTo("lookup_inventory");
        assertThat(functionTool.description()).contains("stock levels");

        assertThat(functionTool.declaration()).isPresent();
        FunctionDeclaration decl = functionTool.declaration().get();
        assertThat(decl.name()).contains("lookup_inventory");
        assertThat(decl.parameters()).isPresent();
    }

    @Test
    @DisplayName("Should execute asynchronously through Google ADK FunctionTool runAsync")
    void shouldExecuteViaAdkRunAsync() {
        FunctionTool functionTool = tool.asFunctionTool();

        Map<String, Object> args = Map.of(
                "sku", "SKU-ELEC-1001",
                "warehouseId", "WH-CENTRAL-01"
        );

        Map<String, Object> response = functionTool.runAsync(args, null).blockingGet();
        assertThat(response).isNotNull();
        assertThat(response.get("status")).isEqualTo("FOUND");
        assertThat(response.get("availableQuantity")).isEqualTo(115);
    }
}

package com.retail.agent.store;

import com.retail.agent.model.CycleCountRecord;
import com.retail.agent.model.ProductSku;
import com.retail.agent.model.ReorderPolicy;
import com.retail.agent.model.StockLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InMemoryRetailInventoryStore Tests")
class InMemoryRetailInventoryStoreTest {

    private InMemoryRetailInventoryStore store;

    @BeforeEach
    void setUp() {
        store = InMemoryRetailInventoryStore.createWithDefaultData();
    }

    @Test
    @DisplayName("Should retrieve all seeded products across categories")
    void shouldRetrieveAllSeededProducts() {
        List<ProductSku> products = store.getAllProducts();
        assertThat(products).hasSize(8);

        Optional<ProductSku> tv = store.findProductBySku("SKU-ELEC-1001");
        assertThat(tv).isPresent();
        assertThat(tv.get().name()).isEqualTo("OmniView 55-Inch 4K OLED Smart TV");
        assertThat(tv.get().category()).isEqualTo("Consumer Electronics");
        assertThat(tv.get().unitPrice()).isEqualByComparingTo("799.99");
    }

    @Test
    @DisplayName("Should find products by case-insensitive category substring")
    void shouldFindProductsByCategory() {
        List<ProductSku> electronics = store.findProductsByCategory("electronics");
        assertThat(electronics).hasSize(3);

        List<ProductSku> apparel = store.findProductsByCategory("APPAREL");
        assertThat(apparel).hasSize(2);

        List<ProductSku> kitchen = store.findProductsByCategory("Home");
        assertThat(kitchen).hasSize(2);

        List<ProductSku> grocery = store.findProductsByCategory("grocery");
        assertThat(grocery).hasSize(1);
    }

    @Test
    @DisplayName("Should retrieve stock levels across all configured warehouses")
    void shouldRetrieveMultiWarehouseStockLevels() {
        List<StockLevel> tvStockAcrossWarehouses = store.getStockLevelsBySku("SKU-ELEC-1001");
        assertThat(tvStockAcrossWarehouses).hasSize(3);

        // Central: 140 on-hand, 25 reserved -> 115 available
        Optional<StockLevel> centralStock = store.getStockLevel("SKU-ELEC-1001", "WH-CENTRAL-01");
        assertThat(centralStock).isPresent();
        assertThat(centralStock.get().onHandQuantity()).isEqualTo(140);
        assertThat(centralStock.get().reservedQuantity()).isEqualTo(25);
        assertThat(centralStock.get().availableQuantity()).isEqualTo(115);

        // West: 8 on-hand, 5 reserved -> 3 available (safety stock breach)
        Optional<StockLevel> westStock = store.getStockLevel("SKU-ELEC-1001", "WH-WEST-03");
        assertThat(westStock).isPresent();
        assertThat(westStock.get().availableQuantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should retrieve reorder policy and detect replenishment alerts")
    void shouldEvaluateReorderPoliciesFromStore() {
        // TV at West Warehouse: ROP 20, Safety 8
        Optional<ReorderPolicy> policyOpt = store.getReorderPolicy("SKU-ELEC-1001", "WH-WEST-03");
        assertThat(policyOpt).isPresent();
        ReorderPolicy policy = policyOpt.get();

        Optional<StockLevel> stockOpt = store.getStockLevel("SKU-ELEC-1001", "WH-WEST-03");
        assertThat(stockOpt).isPresent();
        int available = stockOpt.get().availableQuantity(); // 3

        assertThat(policy.isReorderNeeded(available)).isTrue();
        assertThat(policy.isSafetyStockBreached(available)).isTrue();
    }

    @Test
    @DisplayName("Should list all available warehouses")
    void shouldListWarehouses() {
        List<String> warehouses = store.getAvailableWarehouses();
        assertThat(warehouses).containsExactly("WH-CENTRAL-01", "WH-EAST-02", "WH-WEST-03");
    }

    @Test
    @DisplayName("Should retrieve historical cycle count records including variance and discrepancy types")
    void shouldRetrieveCycleCountAuditHistory() {
        List<CycleCountRecord> eastCounts = store.getCycleCountsByWarehouse("WH-EAST-02");
        assertThat(eastCounts).isNotEmpty();

        // Check shrinkage record for headphones at East
        List<CycleCountRecord> headphoneHistory = store.getCycleCountHistory("SKU-ELEC-1002", "WH-EAST-02");
        assertThat(headphoneHistory).hasSize(1);
        CycleCountRecord record = headphoneHistory.get(0);
        assertThat(record.physicalCount()).isEqualTo(95);
        assertThat(record.systemExpectedQuantity()).isEqualTo(102);
        assertThat(record.variance()).isEqualTo(-7);
        assertThat(record.discrepancyType()).isEqualTo(CycleCountRecord.DiscrepancyType.SHRINKAGE);
    }

    @Test
    @DisplayName("Should update stock quantities atomically")
    void shouldUpdateStockCorrectly() {
        store.updateStock("SKU-ELEC-1001", "WH-CENTRAL-01", 160, 30);

        Optional<StockLevel> updated = store.getStockLevel("SKU-ELEC-1001", "WH-CENTRAL-01");
        assertThat(updated).isPresent();
        assertThat(updated.get().onHandQuantity()).isEqualTo(160);
        assertThat(updated.get().reservedQuantity()).isEqualTo(30);
        assertThat(updated.get().availableQuantity()).isEqualTo(130);
    }

    @Test
    @DisplayName("Should throw exception when updating stock for unregistered SKU")
    void shouldRejectUpdateForUnregisteredSku() {
        assertThatThrownBy(() -> store.updateStock("SKU-UNKNOWN-999", "WH-CENTRAL-01", 10, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown SKU");
    }

    @Test
    @DisplayName("Should record new cycle count and append to audit history")
    void shouldRecordNewCycleCount() {
        CycleCountRecord newRecord = new CycleCountRecord(
                "CC-REC-9999",
                "SKU-ELEC-1001",
                "WH-CENTRAL-01",
                142,
                140,
                Instant.now(),
                "AUDITOR-TEST",
                "Surplus test verification"
        );

        store.recordCycleCount(newRecord);

        List<CycleCountRecord> history = store.getCycleCountHistory("SKU-ELEC-1001", "WH-CENTRAL-01");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).recordId()).isEqualTo("CC-REC-9999");
        assertThat(history.get(0).variance()).isEqualTo(2);
        assertThat(history.get(0).discrepancyType()).isEqualTo(CycleCountRecord.DiscrepancyType.SURPLUS);
    }

    @Test
    @DisplayName("Should register new product and configure reorder policy")
    void shouldRegisterNewProductAndPolicy() {
        ProductSku newProd = new ProductSku(
                "SKU-IOT-5001",
                "Smart Home Hub Pro",
                "Smart Home",
                new BigDecimal("119.00"),
                "098765432109",
                "EACH"
        );

        store.registerProduct(newProd);
        Optional<ProductSku> fetched = store.findProductBySku("SKU-IOT-5001");
        assertThat(fetched).isPresent();

        ReorderPolicy policy = new ReorderPolicy("SKU-IOT-5001", "WH-CENTRAL-01", 50, 20, 100, 7);
        store.setReorderPolicy(policy);

        Optional<ReorderPolicy> fetchedPolicy = store.getReorderPolicy("SKU-IOT-5001", "WH-CENTRAL-01");
        assertThat(fetchedPolicy).isPresent();
        assertThat(fetchedPolicy.get().reorderPoint()).isEqualTo(50);
    }

    @Test
    @DisplayName("Should support empty store initialization for clean testing")
    void shouldSupportEmptyStore() {
        InMemoryRetailInventoryStore emptyStore = InMemoryRetailInventoryStore.createEmpty();
        assertThat(emptyStore.getAllProducts()).isEmpty();
        assertThat(emptyStore.getAvailableWarehouses()).isEmpty();
    }
}

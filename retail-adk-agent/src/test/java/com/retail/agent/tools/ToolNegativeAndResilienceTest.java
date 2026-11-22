package com.retail.agent.tools;

import com.retail.agent.model.ProductSku;
import com.retail.agent.model.StockLevel;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Tool Negative Input & Concurrency Resilience Tests")
class ToolNegativeAndResilienceTest {

    private InMemoryRetailInventoryStore store;
    private InventoryLookupTool lookupTool;
    private DiscrepancyInvestigationTool discrepancyTool;
    private ReplenishmentCalculatorTool replenishmentTool;
    private StockSummaryTool summaryTool;

    @BeforeEach
    void setUp() {
        store = InMemoryRetailInventoryStore.createWithDefaultData();
        lookupTool = new InventoryLookupTool(store);
        discrepancyTool = new DiscrepancyInvestigationTool(store);
        replenishmentTool = new ReplenishmentCalculatorTool(store);
        summaryTool = new StockSummaryTool(store);
    }

    @Test
    @DisplayName("Should defensively handle SQL injection, script injection, and extreme strings")
    void shouldHandleMaliciousAndExtremeInputsSafely() {
        String sqlInjection = "SKU-ELEC-1001'; DROP TABLE inventory; --";
        String xssPayload = "<script>alert('xss')</script>";
        String largeString = "SKU-" + "A".repeat(4000);

        for (String maliciousInput : List.of(sqlInjection, xssPayload, largeString)) {
            var lookupRes = lookupTool.lookupInventory(maliciousInput, "WH-CENTRAL-01");
            assertThat(lookupRes.status()).isEqualTo("SKU_NOT_FOUND");

            var discRes = discrepancyTool.investigateDiscrepancy(maliciousInput, "WH-CENTRAL-01");
            assertThat(discRes.status()).isEqualTo("SKU_NOT_FOUND");

            var replRes = replenishmentTool.calculateReplenishment(maliciousInput, "WH-CENTRAL-01");
            assertThat(replRes.status()).isEqualTo("SKU_NOT_FOUND");
        }
    }

    @Test
    @DisplayName("Should normalize whitespace and case in lookups")
    void shouldNormalizeWhitespaceAndCase() {
        // Lowercase and padded whitespace
        var result = lookupTool.lookupInventory("  sku-elec-1001  ", "  wh-central-01  ");

        assertThat(result.status()).isEqualTo("FOUND");
        assertThat(result.sku()).isEqualTo("SKU-ELEC-1001");
        assertThat(result.warehouseId()).isEqualTo("WH-CENTRAL-01");
        assertThat(result.availableQuantity()).isEqualTo(115);
    }

    @Test
    @DisplayName("Should enforce domain invariants when store mutations violate stock rules")
    void shouldEnforceStockInvariants() {
        // Attempting to set reservedQuantity > onHandQuantity must be rejected
        assertThatThrownBy(() -> store.updateStock("SKU-ELEC-1001", "WH-CENTRAL-01", 100, 150))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed on-hand quantity");

        // Negative numbers must be rejected
        assertThatThrownBy(() -> store.updateStock("SKU-ELEC-1001", "WH-CENTRAL-01", -50, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    @DisplayName("Should handle empty store queries gracefully")
    void shouldHandleEmptyStoreQueries() {
        InMemoryRetailInventoryStore emptyStore = InMemoryRetailInventoryStore.createEmpty();
        InventoryLookupTool emptyLookup = new InventoryLookupTool(emptyStore);
        StockSummaryTool emptySummary = new StockSummaryTool(emptyStore);

        var lookupRes = emptyLookup.lookupInventory("SKU-ELEC-1001", "WH-CENTRAL-01");
        assertThat(lookupRes.status()).isEqualTo("SKU_NOT_FOUND");

        var summaryRes = emptySummary.summarizeStock(null, null);
        assertThat(summaryRes.status()).isEqualTo("NO_DATA");
        assertThat(summaryRes.totalItemsEvaluated()).isZero();
    }

    @Test
    @DisplayName("Should execute concurrently across multiple threads without data races")
    void shouldHandleConcurrentToolExecutions() throws Exception {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Callable<Boolean>> tasks = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            tasks.add(() -> {
                String wh = (index % 2 == 0) ? "WH-CENTRAL-01" : "WH-EAST-02";
                var lookup = lookupTool.lookupInventory("SKU-ELEC-1001", wh);
                var repl = replenishmentTool.calculateReplenishment("SKU-ELEC-1001", wh);
                var summary = summaryTool.summarizeStock(wh, "Consumer Electronics");

                return "FOUND".equals(lookup.status())
                        && "SUCCESS".equals(repl.status())
                        && "SUCCESS".equals(summary.status());
            });
        }

        List<Future<Boolean>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        for (Future<Boolean> future : futures) {
            assertThat(future.get()).isTrue();
        }
    }
}

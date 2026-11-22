package com.retail.agent.tools;

import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.retail.agent.model.ProductSku;
import com.retail.agent.model.ReorderPolicy;
import com.retail.agent.model.StockLevel;
import com.retail.agent.store.RetailInventoryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Google ADK Tool for aggregating and analyzing inventory stock balances across
 * multiple warehouses, categorizing item health into HEALTHY, LOW_STOCK, and CRITICAL_STOCK.
 */
public class StockSummaryTool {

    private final RetailInventoryStore store;

    public StockSummaryTool(RetailInventoryStore store) {
        this.store = Objects.requireNonNull(store, "RetailInventoryStore cannot be null");
    }

    /**
     * Generates a multi-location stock health summary filtered optionally by warehouse or category.
     */
    @Annotations.Schema(
            name = "summarize_stock",
            description = "Summarize inventory across warehouses and categories, identifying low-stock and critical-stockout situations with structured deterministic metrics."
    )
    public StockSummaryResult summarizeStock(
            @Annotations.Schema(name = "warehouseId", description = "Optional warehouse identifier filter (e.g. WH-WEST-03, or empty string/null for all warehouses)", optional = true)
            String warehouseId,
            @Annotations.Schema(name = "category", description = "Optional product category filter (e.g. Consumer Electronics, or empty string/null for all categories)", optional = true)
            String category
    ) {
        boolean filterWarehouse = warehouseId != null && !warehouseId.isBlank() && !"ALL".equalsIgnoreCase(warehouseId.trim());
        boolean filterCategory = category != null && !category.isBlank() && !"ALL".equalsIgnoreCase(category.trim());

        String cleanWarehouseId = filterWarehouse ? warehouseId.trim().toUpperCase(Locale.ROOT) : "ALL";
        String cleanCategory = filterCategory ? category.trim().toLowerCase(Locale.ROOT) : "ALL";

        if (filterWarehouse) {
            boolean validWarehouse = store.getAvailableWarehouses().stream()
                    .anyMatch(w -> w.equalsIgnoreCase(cleanWarehouseId));
            if (!validWarehouse) {
                return new StockSummaryResult(
                        cleanWarehouseId, cleanCategory, 0, 0, 0, 0, 0, 0, 0, List.of(),
                        "INVALID_INPUT",
                        "Warehouse '" + cleanWarehouseId + "' not recognized. Valid warehouses: " + store.getAvailableWarehouses()
                );
            }
        }

        List<String> targetWarehouses = filterWarehouse
                ? List.of(cleanWarehouseId)
                : store.getAvailableWarehouses();

        List<StockItemSummary> itemSummaries = new ArrayList<>();
        int totalOnHand = 0;
        int totalReserved = 0;
        int totalAvailable = 0;
        int criticalCount = 0;
        int lowCount = 0;
        int healthyCount = 0;

        for (String wh : targetWarehouses) {
            List<StockLevel> stocks = store.getStockLevelsByWarehouse(wh);

            for (StockLevel stock : stocks) {
                Optional<ProductSku> productOpt = store.findProductBySku(stock.sku());
                if (productOpt.isEmpty()) {
                    continue;
                }
                ProductSku product = productOpt.get();

                if (filterCategory && !product.category().toLowerCase(Locale.ROOT).contains(cleanCategory)) {
                    continue;
                }

                int onHand = stock.onHandQuantity();
                int reserved = stock.reservedQuantity();
                int available = stock.availableQuantity();

                totalOnHand += onHand;
                totalReserved += reserved;
                totalAvailable += available;

                Optional<ReorderPolicy> policyOpt = store.getReorderPolicy(stock.sku(), wh);
                int rPoint = policyOpt.map(ReorderPolicy::reorderPoint).orElse(0);
                int sStock = policyOpt.map(ReorderPolicy::safetyStock).orElse(0);

                String health;
                if (policyOpt.isPresent()) {
                    ReorderPolicy policy = policyOpt.get();
                    if (policy.isSafetyStockBreached(available)) {
                        health = "CRITICAL_STOCK";
                        criticalCount++;
                    } else if (policy.isReorderNeeded(available)) {
                        health = "LOW_STOCK";
                        lowCount++;
                    } else {
                        health = "HEALTHY";
                        healthyCount++;
                    }
                } else {
                    health = "UNMONITORED";
                    healthyCount++;
                }

                itemSummaries.add(new StockItemSummary(
                        product.sku(),
                        product.name(),
                        product.category(),
                        wh,
                        onHand,
                        reserved,
                        available,
                        rPoint,
                        sStock,
                        health
                ));
            }
        }

        if (itemSummaries.isEmpty()) {
            return new StockSummaryResult(
                    cleanWarehouseId, cleanCategory, 0, 0, 0, 0, 0, 0, 0, List.of(),
                    "NO_DATA", "No inventory items matched the specified filter criteria."
            );
        }

        String summaryMessage = String.format(
                "Evaluated %d stock positions across %s warehouse(s). Critical items: %d, Low-stock items: %d, Healthy items: %d. Total available units: %d.",
                itemSummaries.size(), cleanWarehouseId, criticalCount, lowCount, healthyCount, totalAvailable
        );

        return new StockSummaryResult(
                cleanWarehouseId,
                cleanCategory,
                itemSummaries.size(),
                totalOnHand,
                totalReserved,
                totalAvailable,
                criticalCount,
                lowCount,
                healthyCount,
                List.copyOf(itemSummaries),
                "SUCCESS",
                summaryMessage
        );
    }

    /**
     * Bridges this component to a Google ADK {@link FunctionTool}.
     */
    public FunctionTool asFunctionTool() {
        return FunctionTool.create(this, "summarizeStock");
    }

    public record StockSummaryResult(
            String warehouseFilter,
            String categoryFilter,
            int totalItemsEvaluated,
            int totalOnHandUnits,
            int totalReservedUnits,
            int totalAvailableUnits,
            int criticalStockCount,
            int lowStockCount,
            int healthyStockCount,
            List<StockItemSummary> items,
            String status,
            String message
    ) {
    }

    public record StockItemSummary(
            String sku,
            String productName,
            String category,
            String warehouseId,
            int onHandQuantity,
            int reservedQuantity,
            int availableQuantity,
            int reorderPoint,
            int safetyStock,
            String stockHealth
    ) {
    }
}

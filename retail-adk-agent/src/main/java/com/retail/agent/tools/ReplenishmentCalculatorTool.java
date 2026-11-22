package com.retail.agent.tools;

import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.retail.agent.model.ProductSku;
import com.retail.agent.model.ReorderPolicy;
import com.retail.agent.model.StockLevel;
import com.retail.agent.store.RetailInventoryStore;

import java.util.Objects;
import java.util.Optional;

/**
 * Google ADK Tool for evaluating inventory reorder thresholds and calculating
 * replenishment quantities based on safety stock, lead times, and available stock.
 * <p>
 * Business Rule: All outputs are strictly calculated recommendations and are
 * explicitly differentiated from placed or confirmed purchase orders.
 */
public class ReplenishmentCalculatorTool {

    public static final String CALCULATION_TYPE = "DETERMINISTIC_ALGORITHMIC_RECOMMENDATION";
    public static final String ORDER_STATUS_DISCLAIMER =
            "CALCULATED_RECOMMENDATION_ONLY. This calculation is a replenishment proposal and does NOT represent an approved or confirmed purchase order.";

    private final RetailInventoryStore store;

    public ReplenishmentCalculatorTool(RetailInventoryStore store) {
        this.store = Objects.requireNonNull(store, "RetailInventoryStore cannot be null");
    }

    /**
     * Evaluates reorder thresholds and calculates replenishment recommendation.
     */
    @Annotations.Schema(
            name = "calculate_replenishment",
            description = "Evaluate replenishment thresholds, reorder points (ROP), safety stock margins, and calculate deterministic restocking recommendations based on supplier lead times."
    )
    public ReplenishmentRecommendationResult calculateReplenishment(
            @Annotations.Schema(name = "sku", description = "Stock Keeping Unit code, e.g. SKU-ELEC-1001", optional = false)
            String sku,
            @Annotations.Schema(name = "warehouseId", description = "Warehouse facility code, e.g. WH-WEST-03", optional = false)
            String warehouseId
    ) {
        if (sku == null || sku.isBlank()) {
            return errorResult("", "", "INVALID_INPUT", "SKU parameter cannot be null or empty.");
        }
        if (warehouseId == null || warehouseId.isBlank()) {
            return errorResult(sku.trim(), "", "INVALID_INPUT", "Warehouse ID parameter cannot be null or empty.");
        }

        String cleanSku = sku.trim();
        String cleanWarehouseId = warehouseId.trim();

        // 1. Verify SKU in catalog
        Optional<ProductSku> productOpt = store.findProductBySku(cleanSku);
        if (productOpt.isEmpty()) {
            return errorResult(cleanSku, cleanWarehouseId, "SKU_NOT_FOUND", "SKU '" + cleanSku + "' not found in catalog.");
        }
        ProductSku product = productOpt.get();

        // 2. Verify warehouse
        boolean warehouseExists = store.getAvailableWarehouses().stream()
                .anyMatch(w -> w.equalsIgnoreCase(cleanWarehouseId));
        if (!warehouseExists) {
            return errorResult(cleanSku, cleanWarehouseId, "WAREHOUSE_NOT_FOUND",
                    "Warehouse '" + cleanWarehouseId + "' not recognized. Valid warehouses: " + store.getAvailableWarehouses());
        }

        // 3. Query stock level
        Optional<StockLevel> stockOpt = store.getStockLevel(cleanSku, cleanWarehouseId);
        if (stockOpt.isEmpty()) {
            return errorResult(cleanSku, cleanWarehouseId, "STOCK_NOT_FOUND",
                    "No active stock record found for SKU '" + cleanSku + "' at warehouse '" + cleanWarehouseId + "'.");
        }
        StockLevel stock = stockOpt.get();

        // 4. Query reorder policy
        Optional<ReorderPolicy> policyOpt = store.getReorderPolicy(cleanSku, cleanWarehouseId);
        if (policyOpt.isEmpty()) {
            return errorResult(cleanSku, cleanWarehouseId, "POLICY_NOT_FOUND",
                    "No replenishment policy configured for SKU '" + cleanSku + "' at warehouse '" + cleanWarehouseId + "'.");
        }
        ReorderPolicy policy = policyOpt.get();

        // 5. Deterministic Calculation
        int availableStock = stock.availableQuantity();
        boolean reorderNeeded = policy.isReorderNeeded(availableStock);
        boolean safetyStockBreached = policy.isSafetyStockBreached(availableStock);

        String urgencyLevel;
        int recommendedOrderQty = 0;

        if (safetyStockBreached) {
            urgencyLevel = "CRITICAL";
            // In critical breach, order policy reorder quantity plus deficit below safety stock
            int deficitBelowSafety = policy.safetyStock() - availableStock;
            recommendedOrderQty = policy.reorderQuantity() + Math.max(0, deficitBelowSafety);
        } else if (reorderNeeded) {
            urgencyLevel = "MEDIUM";
            // Normal reorder: order standard reorder batch plus any deficit below ROP
            int deficitBelowRop = policy.reorderPoint() - availableStock;
            recommendedOrderQty = policy.reorderQuantity() + Math.max(0, deficitBelowRop);
        } else {
            urgencyLevel = "NORMAL";
            recommendedOrderQty = 0;
        }

        String message = String.format(
                "Evaluated SKU %s at %s. Available: %d, ROP: %d, Safety Stock: %d. Urgency: %s. Recommended Order: %d units (Lead Time: %d days).",
                cleanSku, cleanWarehouseId, availableStock, policy.reorderPoint(), policy.safetyStock(),
                urgencyLevel, recommendedOrderQty, policy.leadTimeDays()
        );

        return new ReplenishmentRecommendationResult(
                cleanSku,
                product.name(),
                cleanWarehouseId,
                stock.onHandQuantity(),
                stock.reservedQuantity(),
                availableStock,
                policy.reorderPoint(),
                policy.safetyStock(),
                policy.leadTimeDays(),
                reorderNeeded,
                safetyStockBreached,
                recommendedOrderQty,
                urgencyLevel,
                CALCULATION_TYPE,
                ORDER_STATUS_DISCLAIMER,
                "SUCCESS",
                message
        );
    }

    private ReplenishmentRecommendationResult errorResult(String sku, String warehouseId, String status, String message) {
        return new ReplenishmentRecommendationResult(
                sku, "", warehouseId, 0, 0, 0, 0, 0, 0,
                false, false, 0, "UNKNOWN",
                CALCULATION_TYPE, ORDER_STATUS_DISCLAIMER, status, message
        );
    }

    /**
     * Bridges this component to a Google ADK {@link FunctionTool}.
     */
    public FunctionTool asFunctionTool() {
        return FunctionTool.create(this, "calculateReplenishment");
    }

    public record ReplenishmentRecommendationResult(
            String sku,
            String productName,
            String warehouseId,
            int onHandQuantity,
            int reservedQuantity,
            int availableQuantity,
            int reorderPoint,
            int safetyStock,
            int leadTimeDays,
            boolean reorderNeeded,
            boolean safetyStockBreached,
            int recommendedOrderQuantity,
            String urgencyLevel,
            String calculationType,
            String orderStatusNote,
            String status,
            String message
    ) {
    }
}

package com.retail.agent.model;

import java.util.Objects;

/**
 * Immutable domain entity encapsulating replenishment thresholds, lead time, and safety buffers.
 */
public record ReorderPolicy(
        String sku,
        String warehouseId,
        int reorderPoint,
        int safetyStock,
        int reorderQuantity,
        int leadTimeDays
) {
    public ReorderPolicy {
        Objects.requireNonNull(sku, "SKU cannot be null");
        Objects.requireNonNull(warehouseId, "Warehouse ID cannot be null");

        if (sku.isBlank()) {
            throw new IllegalArgumentException("SKU cannot be blank");
        }
        if (warehouseId.isBlank()) {
            throw new IllegalArgumentException("Warehouse ID cannot be blank");
        }
        if (reorderPoint < 0) {
            throw new IllegalArgumentException("Reorder point cannot be negative: " + reorderPoint);
        }
        if (safetyStock < 0) {
            throw new IllegalArgumentException("Safety stock cannot be negative: " + safetyStock);
        }
        if (safetyStock > reorderPoint) {
            throw new IllegalArgumentException(
                    "Safety stock (" + safetyStock + ") cannot exceed reorder point (" + reorderPoint + ")");
        }
        if (reorderQuantity <= 0) {
            throw new IllegalArgumentException("Reorder quantity must be positive: " + reorderQuantity);
        }
        if (leadTimeDays < 0) {
            throw new IllegalArgumentException("Lead time days cannot be negative: " + leadTimeDays);
        }
    }

    /**
     * Determines whether available stock has reached or fallen below the reorder point.
     */
    public boolean isReorderNeeded(int availableStock) {
        return availableStock <= reorderPoint;
    }

    /**
     * Determines whether available stock has breached the safety stock threshold,
     * indicating imminent stockout risk.
     */
    public boolean isSafetyStockBreached(int availableStock) {
        return availableStock < safetyStock;
    }
}

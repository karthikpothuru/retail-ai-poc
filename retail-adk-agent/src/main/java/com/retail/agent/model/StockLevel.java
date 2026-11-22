package com.retail.agent.model;

import java.util.Objects;

/**
 * Immutable domain entity representing stock balance at a specific warehouse location.
 */
public record StockLevel(
        String sku,
        String warehouseId,
        int onHandQuantity,
        int reservedQuantity
) {
    public StockLevel {
        Objects.requireNonNull(sku, "SKU cannot be null");
        Objects.requireNonNull(warehouseId, "Warehouse ID cannot be null");

        if (sku.isBlank()) {
            throw new IllegalArgumentException("SKU cannot be blank");
        }
        if (warehouseId.isBlank()) {
            throw new IllegalArgumentException("Warehouse ID cannot be blank");
        }
        if (onHandQuantity < 0) {
            throw new IllegalArgumentException("On-hand quantity cannot be negative: " + onHandQuantity);
        }
        if (reservedQuantity < 0) {
            throw new IllegalArgumentException("Reserved quantity cannot be negative: " + reservedQuantity);
        }
        if (reservedQuantity > onHandQuantity) {
            throw new IllegalArgumentException(
                    "Reserved quantity (" + reservedQuantity + ") cannot exceed on-hand quantity (" + onHandQuantity + ")");
        }
    }

    /**
     * Calculates Available-to-Promise (ATP) stock for order fulfillment.
     *
     * @return quantity available (onHand - reserved)
     */
    public int availableQuantity() {
        return onHandQuantity - reservedQuantity;
    }

    /**
     * Returns a new StockLevel instance reflecting updated stock quantities.
     */
    public StockLevel withUpdatedStock(int newOnHand, int newReserved) {
        return new StockLevel(sku, warehouseId, newOnHand, newReserved);
    }
}

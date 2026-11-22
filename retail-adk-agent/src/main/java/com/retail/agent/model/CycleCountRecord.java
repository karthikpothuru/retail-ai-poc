package com.retail.agent.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable domain entity representing a physical cycle count event and variance audit.
 */
public record CycleCountRecord(
        String recordId,
        String sku,
        String warehouseId,
        int physicalCount,
        int systemExpectedQuantity,
        Instant countTimestamp,
        String auditorId,
        String notes
) {
    public CycleCountRecord {
        Objects.requireNonNull(recordId, "Record ID cannot be null");
        Objects.requireNonNull(sku, "SKU cannot be null");
        Objects.requireNonNull(warehouseId, "Warehouse ID cannot be null");
        Objects.requireNonNull(countTimestamp, "Count timestamp cannot be null");
        Objects.requireNonNull(auditorId, "Auditor ID cannot be null");

        if (recordId.isBlank()) {
            throw new IllegalArgumentException("Record ID cannot be blank");
        }
        if (sku.isBlank()) {
            throw new IllegalArgumentException("SKU cannot be blank");
        }
        if (warehouseId.isBlank()) {
            throw new IllegalArgumentException("Warehouse ID cannot be blank");
        }
        if (physicalCount < 0) {
            throw new IllegalArgumentException("Physical count cannot be negative: " + physicalCount);
        }
        if (systemExpectedQuantity < 0) {
            throw new IllegalArgumentException("System expected quantity cannot be negative: " + systemExpectedQuantity);
        }
        if (auditorId.isBlank()) {
            throw new IllegalArgumentException("Auditor ID cannot be blank");
        }
        if (notes == null) {
            notes = "";
        }
    }

    /**
     * Calculates variance between physical count and system records.
     * Negative value indicates shrinkage/loss; positive indicates surplus/found stock.
     */
    public int variance() {
        return physicalCount - systemExpectedQuantity;
    }

    /**
     * Checks if any variance exists between physical and system count.
     */
    public boolean hasDiscrepancy() {
        return variance() != 0;
    }

    /**
     * Categorizes the discrepancy type.
     */
    public DiscrepancyType discrepancyType() {
        int v = variance();
        if (v < 0) {
            return DiscrepancyType.SHRINKAGE;
        } else if (v > 0) {
            return DiscrepancyType.SURPLUS;
        } else {
            return DiscrepancyType.MATCH;
        }
    }

    public enum DiscrepancyType {
        MATCH,
        SHRINKAGE,
        SURPLUS
    }
}

package com.retail.agent.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable domain entity representing a Stock Keeping Unit (SKU) in the retail catalog.
 */
public record ProductSku(
        String sku,
        String name,
        String category,
        BigDecimal unitPrice,
        String barcode,
        String unitOfMeasure
) {
    public ProductSku {
        Objects.requireNonNull(sku, "SKU cannot be null");
        Objects.requireNonNull(name, "Product name cannot be null");
        Objects.requireNonNull(category, "Category cannot be null");
        Objects.requireNonNull(unitPrice, "Unit price cannot be null");
        Objects.requireNonNull(barcode, "Barcode cannot be null");
        Objects.requireNonNull(unitOfMeasure, "Unit of measure cannot be null");

        if (sku.isBlank()) {
            throw new IllegalArgumentException("SKU cannot be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("Product name cannot be blank");
        }
        if (category.isBlank()) {
            throw new IllegalArgumentException("Category cannot be blank");
        }
        if (unitPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Unit price cannot be negative: " + unitPrice);
        }
        if (barcode.isBlank()) {
            throw new IllegalArgumentException("Barcode cannot be blank");
        }
        if (unitOfMeasure.isBlank()) {
            throw new IllegalArgumentException("Unit of measure cannot be blank");
        }
    }
}

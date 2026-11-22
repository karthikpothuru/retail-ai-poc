package com.retail.inventory.model;

public record Inventory(
        String productId,
        String productName,
        int expectedInventory,
        int actualInventory,
        int reorderThreshold
) {
}

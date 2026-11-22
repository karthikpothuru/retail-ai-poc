package com.retail.inventory.model;

public record SalesSummary(
        String productId,
        String fromDate,
        String toDate,
        int quantitySold,
        String dataNote
) {
}

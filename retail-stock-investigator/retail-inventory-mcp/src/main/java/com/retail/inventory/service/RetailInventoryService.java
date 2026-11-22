package com.retail.inventory.service;

import com.retail.inventory.model.Inventory;
import com.retail.inventory.model.SalesSummary;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;

@Service
public class RetailInventoryService {

    private final Map<String, Inventory> inventory = Map.of(
            "WM-101", new Inventory("WM-101", "Wireless Mouse", 120, 105, 30),
            "KB-201", new Inventory("KB-201", "Mechanical Keyboard", 80, 15, 20),
            "MN-301", new Inventory("MN-301", "Computer Monitor", 60, 55, 15)
    );

    private final Map<String, Integer> salesByProduct = Map.of(
            "WM-101", 15,
            "KB-201", 12,
            "MN-301", 5
    );

    public Inventory getInventory(String productId) {
        return inventory.get(normalizeProductId(productId));
    }

    public SalesSummary getSalesSummary(String productId, String fromDate, String toDate) {
        String normalizedId = normalizeProductId(productId);
        requireKnownProduct(normalizedId);
        LocalDate from = parseDate(fromDate, "fromDate");
        LocalDate to = parseDate(toDate, "toDate");

        if (to.isBefore(from)) {
            throw new IllegalArgumentException("toDate must be on or after fromDate");
        }

        // Demonstration data: this sample quantity is not date-filtered.
        return new SalesSummary(
                normalizedId,
                from.toString(),
                to.toString(),
                salesByProduct.get(normalizedId),
                "Sample quantity; not calculated from date-filtered transactions"
        );
    }

    public Map<String, Object> getReorderStatus(String productId) {
        Inventory item = requireKnownProduct(normalizeProductId(productId));
        boolean reorderRequired = item.actualInventory() <= item.reorderThreshold();

        return Map.of(
                "productId", item.productId(),
                "productName", item.productName(),
                "availableStock", item.actualInventory(),
                "reorderThreshold", item.reorderThreshold(),
                "reorderRequired", reorderRequired,
                "message", reorderRequired
                        ? "Available stock is at or below the reorder threshold"
                        : "Available stock is above the reorder threshold"
        );
    }

    private Inventory requireKnownProduct(String productId) {
        Inventory item = inventory.get(productId);
        if (item == null) {
            throw new IllegalArgumentException("Product not found: " + productId);
        }
        return item;
    }

    private String normalizeProductId(String productId) {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId is required");
        }
        return productId.trim().toUpperCase(Locale.ROOT);
    }

    private LocalDate parseDate(String value, String fieldName) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException ex) {
            throw new IllegalArgumentException(
                    fieldName + " must be a valid date in yyyy-MM-dd format");
        }
    }
}

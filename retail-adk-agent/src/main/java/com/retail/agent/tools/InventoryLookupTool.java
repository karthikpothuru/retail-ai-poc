package com.retail.agent.tools;

import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.retail.agent.model.ProductSku;
import com.retail.agent.model.StockLevel;
import com.retail.agent.store.RetailInventoryStore;

import java.util.Objects;
import java.util.Optional;

/**
 * Google ADK Tool for looking up real-time retail inventory stock balances across warehouses.
 */
public class InventoryLookupTool {

    private final RetailInventoryStore store;

    public InventoryLookupTool(RetailInventoryStore store) {
        this.store = Objects.requireNonNull(store, "RetailInventoryStore cannot be null");
    }

    /**
     * Executes inventory stock lookup for a specified SKU and warehouse location.
     */
    @Annotations.Schema(
            name = "lookup_inventory",
            description = "Retrieve current inventory stock levels (on-hand, reserved, and available quantities) for a specified SKU at a designated warehouse."
    )
    public InventoryLookupResult lookupInventory(
            @Annotations.Schema(name = "sku", description = "Stock Keeping Unit code, e.g. SKU-ELEC-1001", optional = false)
            String sku,
            @Annotations.Schema(name = "warehouseId", description = "Warehouse facility code, e.g. WH-CENTRAL-01", optional = false)
            String warehouseId
    ) {
        if (sku == null || sku.isBlank()) {
            return new InventoryLookupResult(
                    "", "", "", "", 0, 0, 0,
                    "INVALID_INPUT", "SKU parameter cannot be null or empty."
            );
        }
        if (warehouseId == null || warehouseId.isBlank()) {
            return new InventoryLookupResult(
                    sku.trim(), "", "", "", 0, 0, 0,
                    "INVALID_INPUT", "Warehouse ID parameter cannot be null or empty."
            );
        }

        String cleanSku = sku.trim();
        String cleanWarehouseId = warehouseId.trim();

        // 1. Verify SKU in catalog
        Optional<ProductSku> productOpt = store.findProductBySku(cleanSku);
        if (productOpt.isEmpty()) {
            return new InventoryLookupResult(
                    cleanSku, "", "", cleanWarehouseId, 0, 0, 0,
                    "SKU_NOT_FOUND", "SKU '" + cleanSku + "' not found in catalog."
            );
        }
        ProductSku product = productOpt.get();

        // 2. Verify warehouse exists
        boolean warehouseExists = store.getAvailableWarehouses().stream()
                .anyMatch(w -> w.equalsIgnoreCase(cleanWarehouseId));
        if (!warehouseExists) {
            return new InventoryLookupResult(
                    cleanSku, product.name(), product.category(), cleanWarehouseId, 0, 0, 0,
                    "WAREHOUSE_NOT_FOUND",
                    "Warehouse '" + cleanWarehouseId + "' not recognized. Valid warehouses: " + store.getAvailableWarehouses()
            );
        }

        // 3. Query stock level
        Optional<StockLevel> stockOpt = store.getStockLevel(cleanSku, cleanWarehouseId);
        if (stockOpt.isEmpty()) {
            return new InventoryLookupResult(
                    cleanSku, product.name(), product.category(), cleanWarehouseId, 0, 0, 0,
                    "STOCK_RECORD_NOT_FOUND",
                    "No active stock record found for SKU '" + cleanSku + "' at warehouse '" + cleanWarehouseId + "'."
            );
        }

        StockLevel stock = stockOpt.get();
        return new InventoryLookupResult(
                product.sku(),
                product.name(),
                product.category(),
                stock.warehouseId(),
                stock.onHandQuantity(),
                stock.reservedQuantity(),
                stock.availableQuantity(),
                "FOUND",
                "Successfully retrieved inventory stock balance."
        );
    }

    /**
     * Bridges this component to a Google ADK {@link FunctionTool}.
     */
    public FunctionTool asFunctionTool() {
        return FunctionTool.create(this, "lookupInventory");
    }

    /**
     * Structured result payload returned to the Gemini model.
     */
    public record InventoryLookupResult(
            String sku,
            String productName,
            String category,
            String warehouseId,
            int onHandQuantity,
            int reservedQuantity,
            int availableQuantity,
            String status,
            String message
    ) {
    }
}

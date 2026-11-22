package com.retail.inventory.tools;

import com.retail.inventory.model.Inventory;
import com.retail.inventory.model.SalesSummary;
import com.retail.inventory.service.RetailInventoryService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class InventoryTools {

    private final RetailInventoryService inventoryService;

    public InventoryTools(RetailInventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @McpTool(
            name = "get_inventory",
            description = "Retrieve current and expected inventory details for a retail product. "
                    + "Use this tool before assessing an inventory discrepancy."
    )
    public Inventory getInventory(
            @McpToolParam(
                    description = "Product ID, for example WM-101, KB-201, or MN-301",
                    required = true
            ) String productId) {
        Inventory result = inventoryService.getInventory(productId);
        if (result == null) {
            throw new IllegalArgumentException("Product not found: " + productId);
        }
        return result;
    }

    @McpTool(
            name = "get_sales_summary",
            description = "Retrieve the sample sales quantity for a product and the requested date range. "
                    + "The returned sample quantity is not date-filtered."
    )
    public SalesSummary getSalesSummary(
            @McpToolParam(description = "Product ID, for example WM-101", required = true)
            String productId,
            @McpToolParam(description = "Start date in yyyy-MM-dd format", required = true)
            String fromDate,
            @McpToolParam(description = "End date in yyyy-MM-dd format", required = true)
            String toDate) {
        return inventoryService.getSalesSummary(productId, fromDate, toDate);
    }

    @McpTool(
            name = "get_reorder_status",
            description = "Check whether available inventory is at or below the product's reorder threshold. "
                    + "This tool reports status only and does not place an order."
    )
    public Map<String, Object> getReorderStatus(
            @McpToolParam(description = "Product ID, for example WM-101", required = true)
            String productId) {
        return inventoryService.getReorderStatus(productId);
    }
}

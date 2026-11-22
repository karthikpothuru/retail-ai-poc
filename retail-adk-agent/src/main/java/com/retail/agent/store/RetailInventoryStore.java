package com.retail.agent.store;

import com.retail.agent.model.CycleCountRecord;
import com.retail.agent.model.ProductSku;
import com.retail.agent.model.ReorderPolicy;
import com.retail.agent.model.StockLevel;

import java.util.List;
import java.util.Optional;

/**
 * Interface defining operations for querying and maintaining retail catalog,
 * warehouse stock levels, cycle count audit histories, and replenishment policies.
 */
public interface RetailInventoryStore {

    /**
     * Looks up a product SKU in the catalog.
     */
    Optional<ProductSku> findProductBySku(String sku);

    /**
     * Retrieves all products currently in the catalog.
     */
    List<ProductSku> getAllProducts();

    /**
     * Finds products belonging to a specific retail category (case-insensitive).
     */
    List<ProductSku> findProductsByCategory(String category);

    /**
     * Retrieves the stock level for a specific SKU at a designated warehouse location.
     */
    Optional<StockLevel> getStockLevel(String sku, String warehouseId);

    /**
     * Retrieves stock levels for a given SKU across all warehouses.
     */
    List<StockLevel> getStockLevelsBySku(String sku);

    /**
     * Retrieves all stock levels within a specific warehouse.
     */
    List<StockLevel> getStockLevelsByWarehouse(String warehouseId);

    /**
     * Retrieves the replenishment policy for a SKU at a given warehouse.
     */
    Optional<ReorderPolicy> getReorderPolicy(String sku, String warehouseId);

    /**
     * Retrieves all reorder policies configured for a specific warehouse.
     */
    List<ReorderPolicy> getReorderPoliciesByWarehouse(String warehouseId);

    /**
     * Retrieves cycle count audit history for a specific SKU at a designated warehouse.
     */
    List<CycleCountRecord> getCycleCountHistory(String sku, String warehouseId);

    /**
     * Retrieves all cycle count audit records logged for a specific warehouse.
     */
    List<CycleCountRecord> getCycleCountsByWarehouse(String warehouseId);

    /**
     * Retrieves the unique identifiers of all registered warehouses.
     */
    List<String> getAvailableWarehouses();

    /**
     * Updates on-hand and reserved quantities for a SKU at a warehouse location.
     */
    void updateStock(String sku, String warehouseId, int onHand, int reserved);

    /**
     * Logs a newly completed cycle count record.
     */
    void recordCycleCount(CycleCountRecord record);

    /**
     * Adds or updates a product in the catalog.
     */
    void registerProduct(ProductSku product);

    /**
     * Adds or updates a replenishment policy for a SKU at a warehouse.
     */
    void setReorderPolicy(ReorderPolicy policy);
}

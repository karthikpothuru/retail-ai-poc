package com.retail.agent.context;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Catalog metadata, cited knowledge and inventory observations have distinct fields. */
public record ProductKnowledgeResponse(Outcome outcome, List<ProductCatalog.Product> products, String answer,
                                       Map<String, InventoryObservation> liveInventoryBySku) {
    public enum Outcome { FOUND, UNKNOWN_SKU, AMBIGUOUS, CLARIFICATION_REQUIRED, COMPARISON_LIMIT, INSUFFICIENT, UNAVAILABLE }

    public enum InventoryStatus {
        REPORTED, WAREHOUSE_UNSUPPORTED, UNKNOWN_WAREHOUSE, NOT_FOUND, TIMEOUT,
        AUTHORIZATION_FAILED, UNAVAILABLE, MALFORMED_RESPONSE, SKU_MISMATCH, REMOTE_ERROR
    }

    public record InventoryObservation(String sku, String warehouseId, Integer actualInventory,
                                       Integer onHandQuantity, Integer reservedQuantity, Integer availableQuantity,
                                       InventoryStatus status, String toolStatus, Instant observedAt, String source,
                                       java.util.UUID requestId) {}

    public ProductKnowledgeResponse {
        products = List.copyOf(products);
        liveInventoryBySku = Map.copyOf(liveInventoryBySku);
    }
}

package com.retail.agent.tools;

import com.google.adk.tools.Annotations;
import com.google.adk.tools.FunctionTool;
import com.retail.agent.model.CycleCountRecord;
import com.retail.agent.model.ProductSku;
import com.retail.agent.store.RetailInventoryStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Google ADK Tool for investigating physical inventory discrepancies and audit history.
 * Provides strictly factual audit evidence without unverified root-cause speculation.
 */
public class DiscrepancyInvestigationTool {

    private final RetailInventoryStore store;

    public DiscrepancyInvestigationTool(RetailInventoryStore store) {
        this.store = Objects.requireNonNull(store, "RetailInventoryStore cannot be null");
    }

    /**
     * Retrieves cycle count audits and computes factual variance metrics.
     */
    @Annotations.Schema(
            name = "investigate_discrepancy",
            description = "Retrieve historical physical cycle count audits and calculate inventory discrepancies (shrinkage, surplus, or matching stock) with documented factual evidence."
    )
    public DiscrepancyInvestigationResult investigateDiscrepancy(
            @Annotations.Schema(name = "sku", description = "Stock Keeping Unit code, e.g. SKU-ELEC-1002", optional = false)
            String sku,
            @Annotations.Schema(name = "warehouseId", description = "Warehouse facility code, e.g. WH-EAST-02", optional = false)
            String warehouseId
    ) {
        if (sku == null || sku.isBlank()) {
            return new DiscrepancyInvestigationResult(
                    "", "", 0, 0, 0, 0, 0, List.of(),
                    "INVALID_INPUT", "SKU parameter cannot be null or empty."
            );
        }
        if (warehouseId == null || warehouseId.isBlank()) {
            return new DiscrepancyInvestigationResult(
                    sku.trim(), "", 0, 0, 0, 0, 0, List.of(),
                    "INVALID_INPUT", "Warehouse ID parameter cannot be null or empty."
            );
        }

        String cleanSku = sku.trim();
        String cleanWarehouseId = warehouseId.trim();

        // 1. Verify SKU in catalog
        Optional<ProductSku> productOpt = store.findProductBySku(cleanSku);
        if (productOpt.isEmpty()) {
            return new DiscrepancyInvestigationResult(
                    cleanSku, cleanWarehouseId, 0, 0, 0, 0, 0, List.of(),
                    "SKU_NOT_FOUND", "SKU '" + cleanSku + "' not found in catalog."
            );
        }

        // 2. Verify warehouse
        boolean warehouseExists = store.getAvailableWarehouses().stream()
                .anyMatch(w -> w.equalsIgnoreCase(cleanWarehouseId));
        if (!warehouseExists) {
            return new DiscrepancyInvestigationResult(
                    cleanSku, cleanWarehouseId, 0, 0, 0, 0, 0, List.of(),
                    "WAREHOUSE_NOT_FOUND",
                    "Warehouse '" + cleanWarehouseId + "' not recognized. Valid warehouses: " + store.getAvailableWarehouses()
            );
        }

        // 3. Retrieve cycle count records
        List<CycleCountRecord> history = store.getCycleCountHistory(cleanSku, cleanWarehouseId);
        if (history.isEmpty()) {
            return new DiscrepancyInvestigationResult(
                    cleanSku, cleanWarehouseId, 0, 0, 0, 0, 0, List.of(),
                    "NO_RECORDS_FOUND",
                    "No historical cycle count records logged for SKU '" + cleanSku + "' at warehouse '" + cleanWarehouseId + "'."
            );
        }

        int matchingCount = 0;
        int shrinkageCount = 0;
        int surplusCount = 0;
        int netVariance = 0;
        List<AuditEvidence> evidenceList = new ArrayList<>();

        for (CycleCountRecord record : history) {
            int variance = record.variance();
            netVariance += variance;

            String classification = switch (record.discrepancyType()) {
                case SHRINKAGE -> {
                    shrinkageCount++;
                    yield "SHRINKAGE";
                }
                case SURPLUS -> {
                    surplusCount++;
                    yield "SURPLUS";
                }
                case MATCH -> {
                    matchingCount++;
                    yield "MATCH";
                }
            };

            evidenceList.add(new AuditEvidence(
                    record.recordId(),
                    record.countTimestamp().toString(),
                    record.physicalCount(),
                    record.systemExpectedQuantity(),
                    variance,
                    classification,
                    record.auditorId(),
                    record.notes()
            ));
        }

        String summary = String.format(
                "Factual audit summary for SKU %s at %s: Total audits: %d. Net unit variance: %d. " +
                        "Shrinkage events: %d, Surplus events: %d, Exact matches: %d.",
                cleanSku, cleanWarehouseId, history.size(), netVariance, shrinkageCount, surplusCount, matchingCount
        );

        return new DiscrepancyInvestigationResult(
                cleanSku,
                cleanWarehouseId,
                history.size(),
                matchingCount,
                shrinkageCount,
                surplusCount,
                netVariance,
                List.copyOf(evidenceList),
                "FOUND",
                summary
        );
    }

    /**
     * Bridges this component to a Google ADK {@link FunctionTool}.
     */
    public FunctionTool asFunctionTool() {
        return FunctionTool.create(this, "investigateDiscrepancy");
    }

    public record DiscrepancyInvestigationResult(
            String sku,
            String warehouseId,
            int totalAuditCount,
            int matchingAuditCount,
            int shrinkageAuditCount,
            int surplusAuditCount,
            int netVarianceUnits,
            List<AuditEvidence> auditRecords,
            String status,
            String summaryEvidence
    ) {
    }

    public record AuditEvidence(
            String recordId,
            String timestamp,
            int physicalCount,
            int systemExpectedQuantity,
            int variance,
            String classification,
            String auditorId,
            String documentedNotes
    ) {
    }
}

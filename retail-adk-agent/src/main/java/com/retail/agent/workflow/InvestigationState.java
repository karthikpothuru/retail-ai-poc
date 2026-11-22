package com.retail.agent.workflow;

import com.google.adk.agents.CallbackContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Invocation-scoped state. Values are JSON-compatible maps, never model-generated summaries. */
final class InvestigationState {
    static final String PREFIX = "temp:retail_investigation:";
    static final String REQUEST = PREFIX + "request";
    static final String REPORT = PREFIX + "report";
    static final List<String> STAGES = List.of("inventory", "discrepancy", "replenishment");

    private InvestigationState() {}

    static Map<String, Object> request(CallbackContext ctx) {
        var request = map(ctx.state().get(REQUEST));
        if (!ctx.invocationId().equals(request.get("invocationId"))) {
            throw new IllegalStateException("Missing or stale investigation request.");
        }
        new InvestigationRequest((String) request.get("sku"), (String) request.get("warehouseId"));
        return request;
    }

    static Map<String, Object> result(CallbackContext ctx, String stage) {
        var envelope = map(ctx.state().get(PREFIX + stage));
        var request = request(ctx);
        if (!request.equals(envelope.get("request"))) {
            throw new IllegalStateException("Missing or stale " + stage + " result.");
        }
        return map(envelope.get("result"));
    }

    static void requirePreceding(CallbackContext ctx, int index) {
        request(ctx);
        for (int previous = 0; previous < index; previous++) {
            result(ctx, STAGES.get(previous));
        }
    }

    static void validate(String stage, Map<String, Object> request, Map<String, Object> result) {
        if (!request.get("sku").equals(result.get("sku"))
                || !request.get("warehouseId").equals(result.get("warehouseId"))) {
            throw new IllegalStateException("Tool returned identifiers outside the investigation request.");
        }
        String status = text(result, "status");
        Set<String> allowed = switch (stage) {
            case "inventory" -> Set.of("FOUND", "SKU_NOT_FOUND", "WAREHOUSE_NOT_FOUND", "STOCK_RECORD_NOT_FOUND");
            case "discrepancy" -> Set.of("FOUND", "NO_RECORDS_FOUND", "SKU_NOT_FOUND", "WAREHOUSE_NOT_FOUND");
            case "replenishment" -> Set.of("SUCCESS", "SKU_NOT_FOUND", "WAREHOUSE_NOT_FOUND", "STOCK_NOT_FOUND", "POLICY_NOT_FOUND");
            default -> throw new IllegalArgumentException("Unknown investigation stage.");
        };
        if (!allowed.contains(status)) {
            throw new IllegalStateException("Unexpected tool result status: " + status);
        }
        if (stage.equals("inventory") && status.equals("FOUND")) {
            integers(result, "onHandQuantity", "reservedQuantity", "availableQuantity");
        } else if (stage.equals("discrepancy") && status.equals("FOUND")) {
            integers(result, "totalAuditCount", "netVarianceUnits", "matchingAuditCount", "shrinkageAuditCount", "surplusAuditCount");
            if (!(result.get("auditRecords") instanceof List<?> records)) {
                throw new IllegalStateException("Missing audit evidence.");
            } else {
                records.forEach(record -> {
                    var audit = map(record);
                    integers(audit, "physicalCount", "systemExpectedQuantity", "variance");
                    for (String key : List.of("recordId", "timestamp", "classification", "auditorId", "documentedNotes")) {
                        text(audit, key);
                    }
                });
            }
        } else if (stage.equals("replenishment") && status.equals("SUCCESS")) {
            integers(result, "onHandQuantity", "reservedQuantity", "availableQuantity", "reorderPoint", "safetyStock",
                    "leadTimeDays", "recommendedOrderQuantity");
            if (!(result.get("reorderNeeded") instanceof Boolean) || !(result.get("safetyStockBreached") instanceof Boolean)) {
                throw new IllegalStateException("Missing replenishment decision.");
            }
            text(result, "urgencyLevel");
        }
    }

    static String text(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof String value)) {
            throw new IllegalStateException("Missing string field: " + key);
        }
        return value;
    }

    private static void integers(Map<String, Object> result, String... keys) {
        for (String key : keys) {
            if (!(result.get(key) instanceof Number number) || number.doubleValue() != number.intValue()) {
                throw new IllegalStateException("Missing integer field: " + key);
            }
        }
    }

    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("Expected structured investigation state.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (!(key instanceof String stringKey)) {
                throw new IllegalStateException("Invalid state key.");
            }
            result.put(stringKey, item);
        });
        return result;
    }

    static Object snapshot(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map(value).forEach((key, item) -> copy.put(key, snapshot(item)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(InvestigationState::snapshot).toList();
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new IllegalStateException("Non-serializable workflow state value.");
    }
}

package com.retail.agent.parallel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Per-invocation result slots; no branch writes to a shared ADK session state map. */
final class WarehouseAssessmentResults {
    static final List<String> WAREHOUSES = List.of("WH-CENTRAL-01", "WH-EAST-02", "WH-WEST-03");
    static final List<String> METRICS = List.of("totalItemsEvaluated", "totalOnHandUnits", "totalReservedUnits",
            "totalAvailableUnits", "criticalStockCount", "lowStockCount", "healthyStockCount");
    private final Map<String, Outcome> slots = new ConcurrentHashMap<>();

    record Outcome(String warehouse, String status, String reason, Map<String, Object> result, double durationMs) {
        Outcome {
            if (!WAREHOUSES.contains(warehouse)) {
                throw new IllegalArgumentException("Unexpected warehouse branch");
            }
            result = map(snapshot(result));
        }

        static Outcome failure(String warehouse, String reason, double durationMs) {
            return new Outcome(warehouse, "FAILED", reason, Map.of(), durationMs);
        }

        Map<String, Object> asMap() {
            return Map.of("warehouseId", warehouse, "status", status, "reason", reason,
                    "result", result, "durationMs", durationMs);
        }
    }

    void publish(Outcome outcome) {
        slots.compute(outcome.warehouse(), (warehouse, previous) -> previous == null ? outcome
                : Outcome.failure(warehouse, "DUPLICATE_OUTPUT", Math.max(previous.durationMs(), outcome.durationMs())));
    }

    Map<String, Object> aggregate(String workflowId, String correlationId) {
        List<Map<String, Object>> branches = new ArrayList<>();
        Map<String, Long> totals = new LinkedHashMap<>();
        METRICS.forEach(metric -> totals.put(metric, 0L));
        int successes = 0;
        for (String warehouse : WAREHOUSES) {
            Outcome outcome = slots.getOrDefault(warehouse, Outcome.failure(warehouse, "MISSING_OUTPUT", 0));
            if (outcome.status().equals("SUCCESS")) {
                try {
                    validate(warehouse, outcome.result());
                    Map<String, Long> next = new LinkedHashMap<>();
                    for (String metric : METRICS) {
                        next.put(metric, Math.addExact(totals.get(metric), number(outcome.result(), metric)));
                    }
                    totals.putAll(next);
                    successes++;
                } catch (RuntimeException invalid) {
                    outcome = Outcome.failure(warehouse, "INVALID_RESULT", outcome.durationMs());
                }
            }
            branches.add(outcome.asMap());
        }
        String status = successes == 3 ? "FULL_SUCCESS" : successes == 0 ? "COMPLETE_FAILURE" : "PARTIAL_SUCCESS";
        return Map.of("workflowId", workflowId, "correlationId", correlationId, "status", status,
                "successfulBranches", successes, "failedBranches", 3 - successes,
                "totalsForSuccessfulWarehousesOnly", successes == 0 ? Map.of() : Map.copyOf(totals),
                "branches", List.copyOf(branches));
    }

    static void validate(String warehouse, Map<String, Object> result) {
        if (!warehouse.equals(result.get("warehouseFilter")) || !"ALL".equals(result.get("categoryFilter"))
                || !"SUCCESS".equals(result.get("status"))) {
            throw new IllegalStateException("Invalid warehouse summary identity or status");
        }
        METRICS.forEach(metric -> number(result, metric));
        if (!(result.get("items") instanceof List<?> items) || items.size() != number(result, "totalItemsEvaluated")) {
            throw new IllegalStateException("Missing or inconsistent summary items");
        }
        long onHand = 0, reserved = 0, available = 0, critical = 0, low = 0, healthy = 0;
        var identities = new java.util.HashSet<String>();
        for (Object item : items) {
            var row = map(item);
            if (!warehouse.equals(row.get("warehouseId")) || !(row.get("sku") instanceof String sku)
                    || sku.isBlank() || !identities.add(sku)) {
                throw new IllegalStateException("Wrong warehouse or duplicate stock position");
            }
            long hand = number(row, "onHandQuantity"), held = number(row, "reservedQuantity");
            long atp = number(row, "availableQuantity");
            if (held > hand || atp != hand - held) {
                throw new IllegalStateException("Inconsistent stock balance");
            }
            onHand = Math.addExact(onHand, hand);
            reserved = Math.addExact(reserved, held);
            available = Math.addExact(available, atp);
            switch (String.valueOf(row.get("stockHealth"))) {
                case "CRITICAL_STOCK" -> critical++;
                case "LOW_STOCK" -> low++;
                case "HEALTHY", "UNMONITORED" -> healthy++;
                default -> throw new IllegalStateException("Invalid stock health");
            }
        }
        if (onHand != number(result, "totalOnHandUnits") || reserved != number(result, "totalReservedUnits")
                || available != number(result, "totalAvailableUnits") || critical != number(result, "criticalStockCount")
                || low != number(result, "lowStockCount") || healthy != number(result, "healthyStockCount")) {
            throw new IllegalStateException("Summary totals disagree with stock evidence");
        }
    }

    static long number(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof Number value) || !Double.isFinite(value.doubleValue())
                || value.doubleValue() != value.longValue() || value.longValue() < 0) {
            throw new IllegalStateException("Invalid numeric summary field");
        }
        return value.longValue();
    }

    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("Expected structured result");
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (!(key instanceof String name)) {
                throw new IllegalStateException("Invalid result key");
            }
            copy.put(name, item);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object snapshot(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map(value).forEach((key, item) -> copy.put(key, snapshot(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> items) {
            return items.stream().map(WarehouseAssessmentResults::snapshot).toList();
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        throw new IllegalStateException("Non-serializable result value");
    }
}

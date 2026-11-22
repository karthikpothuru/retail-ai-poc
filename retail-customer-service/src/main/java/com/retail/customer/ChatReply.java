package com.retail.customer;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.retail.customer.ChatFailure.Reason.INVALID_ADK_RESPONSE;

record ChatReply(String answer, List<Citation> citations, List<InventoryObservation> inventoryObservations,
                 List<String> productSkus, boolean personalized, String status, ErrorInfo error) {
    record Citation(String marker, String channel) {}
    record InventoryObservation(String sku, String warehouseId, Integer actualInventory, Integer onHandQuantity,
                                Integer reservedQuantity, Integer availableQuantity, String status, String toolStatus,
                                String observedAt, String source, String requestId) {}
    record ErrorInfo(String code, String message) {}

    private static final String UUID_PATTERN = "[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}";
    private static final Pattern MARKER = Pattern.compile("\\[DOC:" + UUID_PATTERN + ":v[1-9][0-9]*:" + UUID_PATTERN + "\\]");
    private static final Pattern SKU = Pattern.compile("[A-Z]{2,8}-[0-9]{2,8}");
    private static final Set<String> INVENTORY_STATES = Set.of("REPORTED", "WAREHOUSE_UNSUPPORTED", "UNKNOWN_WAREHOUSE",
            "NOT_FOUND", "TIMEOUT", "AUTHORIZATION_FAILED", "UNAVAILABLE", "MALFORMED_RESPONSE", "SKU_MISMATCH", "REMOTE_ERROR");
    private static final Set<String> TOOL_STATES = Set.of("SUCCESS", "REMOTE_ERROR", "TIMEOUT_UNKNOWN",
            "AUTHORIZATION_FAILED", "CONNECTION_FAILURE", "GATEWAY_FAILURE", "NOT_CALLED", "DENIED",
            "CANCELLED_UNKNOWN", "MALFORMED_RESPONSE");

    static ChatReply error(ChatFailure.Reason reason) {
        return new ChatReply("", List.of(), List.of(), List.of(), false, "ERROR",
                new ErrorInfo(reason.name(), reason.message));
    }

    static ChatReply decode(JsonNode root) {
        if (root == null || !root.isObject() || root.size() != 6 || !root.path("personalized").isBoolean()) {
            throw invalid();
        }
        String answer = text(root, "answer", 16000);
        String status = text(root, "status", 20);
        if (!Set.of("OK", "PARTIAL", "INSUFFICIENT").contains(status)) {
            throw invalid();
        }
        var skus = new ArrayList<String>();
        for (var node : array(root, "productSkus", 3)) {
            if (!node.isTextual() || !SKU.matcher(node.textValue()).matches() || skus.contains(node.textValue())) {
                throw invalid();
            }
            skus.add(node.textValue());
        }
        var citations = new ArrayList<Citation>();
        var declared = new HashSet<String>();
        for (var node : array(root, "citations", 20)) {
            String marker = text(node, "marker", 128);
            String channel = text(node, "channel", 16);
            if (node.size() != 2 || !MARKER.matcher(marker).matches() || !declared.add(marker)
                    || !Set.of("REGISTRATION", "PRODUCT").contains(channel)) {
                throw invalid();
            }
            citations.add(new Citation(marker, channel));
        }
        var markers = MARKER.matcher(answer).results().map(java.util.regex.MatchResult::group).toList();
        long openings = Pattern.compile("\\[DOC:").matcher(answer).results().count();
        if (openings != markers.size() || !declared.equals(new HashSet<>(markers))) {
            throw invalid();
        }
        var inventory = new ArrayList<InventoryObservation>();
        var observedSkus = new HashSet<String>();
        for (var node : array(root, "inventoryObservations", 3)) {
            var item = observation(node);
            if (!skus.contains(item.sku()) || !observedSkus.add(item.sku())) {
                throw invalid();
            }
            inventory.add(item);
        }
        return new ChatReply(answer, List.copyOf(citations), List.copyOf(inventory), List.copyOf(skus),
                root.path("personalized").booleanValue(), status, null);
    }

    private static InventoryObservation observation(JsonNode node) {
        if (!node.isObject() || node.size() != 11 || !node.path("onHandQuantity").isNull()
                || !node.path("reservedQuantity").isNull() || !node.path("availableQuantity").isNull()) {
            throw invalid();
        }
        String sku = text(node, "sku", 24);
        String status = text(node, "status", 32);
        String toolStatus = text(node, "toolStatus", 32);
        if (!INVENTORY_STATES.contains(status) || !TOOL_STATES.contains(toolStatus)) {
            throw invalid();
        }
        String warehouse = nullableText(node, "warehouseId", 33);
        if (warehouse != null && (!warehouse.matches("WH-[A-Z0-9-]{1,30}")
                || !Set.of("WAREHOUSE_UNSUPPORTED", "UNKNOWN_WAREHOUSE").contains(status))) {
            throw invalid();
        }
        var countNode = node.path("actualInventory");
        Integer count = null;
        if ("REPORTED".equals(status)) {
            if (!countNode.isIntegralNumber() || !countNode.canConvertToInt() || countNode.intValue() < 0
                    || !"SUCCESS".equals(toolStatus) || warehouse != null) {
                throw invalid();
            }
            count = countNode.intValue();
        } else if (!countNode.isNull()) {
            throw invalid();
        }
        String timestamp = nullableText(node, "observedAt", 40);
        String source = nullableText(node, "source", 48);
        String requestId = nullableText(node, "requestId", 36);
        if ("NOT_CALLED".equals(toolStatus)) {
            if (timestamp != null || source != null || requestId != null) {
                throw invalid();
            }
        } else {
            if (!"local-stock/get_inventory".equals(source) || timestamp == null || requestId == null
                    || !requestId.matches(UUID_PATTERN)) {
                throw invalid();
            }
            try {
                Instant.parse(timestamp);
                UUID.fromString(requestId);
            } catch (RuntimeException badTimestamp) {
                throw invalid();
            }
        }
        return new InventoryObservation(sku, warehouse, count, null, null, null, status, toolStatus,
                timestamp, source, requestId);
    }

    private static List<JsonNode> array(JsonNode root, String field, int limit) {
        var node = root.path(field);
        if (!node.isArray() || node.size() > limit) throw invalid();
        var values = new ArrayList<JsonNode>();
        node.forEach(values::add);
        return values;
    }

    private static String nullableText(JsonNode root, String field, int size) {
        return root.path(field).isNull() ? null : text(root, field, size);
    }

    private static String text(JsonNode root, String field, int size) {
        var node = root.path(field);
        if (!node.isTextual() || node.textValue().isBlank() || node.textValue().length() > size) throw invalid();
        return node.textValue();
    }

    private static ChatFailure invalid() {
        return new ChatFailure(INVALID_ADK_RESPONSE);
    }
}

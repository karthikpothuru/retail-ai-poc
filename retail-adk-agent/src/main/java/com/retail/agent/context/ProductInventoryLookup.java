package com.retail.agent.context;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.retail.agent.context.ProductKnowledgeResponse.InventoryStatus.*;
import com.retail.agent.context.ProductKnowledgeResponse.InventoryObservation;
import com.retail.agent.context.ProductKnowledgeResponse.InventoryStatus;

/** Host-side adapter for the pinned, product-level Spring MCP contract. */
final class ProductInventoryLookup {
    static final String ALIAS = "mcp_get_inventory";
    private static final Set<String> SERVER_SKUS = Set.of("WM-101", "KB-201", "MN-301");
    private static final Set<String> NATIVE_WAREHOUSES = Set.of("WH-CENTRAL-01", "WH-EAST-02", "WH-WEST-03");
    private static final Map<String, String> WAREHOUSE_NAMES = Map.of(
            "central", "WH-CENTRAL-01", "east", "WH-EAST-02", "west", "WH-WEST-03");
    private static final Pattern WAREHOUSE_CODE = Pattern.compile("(?i)\\bWH-[a-z0-9-]{1,30}\\b");
    private static final Pattern WAREHOUSE_NAME = Pattern.compile(
            "\\b(central|east|west)\\s+warehouse\\b|\\bwarehouse\\s+(central|east|west)\\b");
    private static final Pattern WAREHOUSE_WORD = Pattern.compile("\\bwarehouses?\\b");
    private static final Pattern STOCK = Pattern.compile(
            "\\b(inventory|stock|availability|on[ -]hand|reserved quantity|available quantity)\\b"
                    + "|\\b(?:is|are)\\b.{0,150}\\bavailable\\s*[?!.]?$"
                    + "|\\b(?:is|are)\\b.{0,150}\\bavailable\\s+(?:in|at)\\b");
    private static final Pattern DETAILS = Pattern.compile(
            "\\b(about|features?|specifications?|specs?|compare|contrast|difference|versus|vs|setup|describe"
                    + "|battery|ports?|usb|hdmi|layout|compatible|typing|connects?)\\b");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> FIELDS = Set.of(
            "productId", "productName", "expectedInventory", "actualInventory", "reorderThreshold");

    record Warehouse(String id, boolean known) {}

    private final SecureMcpGateway gateway;
    private final TrustedCallerBoundary.Caller caller;
    private final Clock clock;

    ProductInventoryLookup(SecureMcpGateway gateway, TrustedCallerBoundary.Caller caller, Clock clock) {
        this.gateway = gateway;
        this.caller = caller;
        this.clock = clock;
    }

    static boolean requested(String question) {
        return STOCK.matcher(question.toLowerCase(Locale.ROOT)).find();
    }

    static boolean wantsDetails(String question) {
        return DETAILS.matcher(question.toLowerCase(Locale.ROOT)).find();
    }

    static Warehouse warehouse(String question) {
        var code = WAREHOUSE_CODE.matcher(question);
        if (code.find()) {
            String id = code.group().toUpperCase(Locale.ROOT);
            return new Warehouse(id, NATIVE_WAREHOUSES.contains(id));
        }
        String lower = question.toLowerCase(Locale.ROOT);
        var name = WAREHOUSE_NAME.matcher(lower);
        if (name.find()) {
            return new Warehouse(WAREHOUSE_NAMES.get(name.group(1) == null ? name.group(2) : name.group(1)), true);
        }
        return WAREHOUSE_WORD.matcher(lower).find() ? new Warehouse(null, false) : null;
    }

    static String withoutWarehouseCodes(String question) {
        return WAREHOUSE_CODE.matcher(question).replaceAll(" ");
    }

    Map<String, InventoryObservation> fetch(List<ProductCatalog.Product> targets) {
        var results = new LinkedHashMap<String, InventoryObservation>();
        try (var session = gateway.bind(caller, UUID.randomUUID())) {
            for (var product : targets) {
                results.put(product.sku(), fetch(session, product));
            }
        } catch (SecurityException denied) {
            for (var product : targets) {
                results.putIfAbsent(product.sku(), notCalled(product.sku(), null, AUTHORIZATION_FAILED));
            }
        } catch (RuntimeException failure) {
            for (var product : targets) {
                results.putIfAbsent(product.sku(), notCalled(product.sku(), null, UNAVAILABLE));
            }
        }
        return results;
    }

    private InventoryObservation fetch(SecureMcpGateway.Session session, ProductCatalog.Product product) {
        String sku = product.sku();
        if (!SERVER_SKUS.contains(sku)) {
            return notCalled(sku, null, NOT_FOUND);
        }
        try {
            var reply = session.execute(ALIAS, Map.of("productId", sku));
            var evidence = latest(session);
            String status = evidence.outcome();
            Instant observedAt = clock.instant();
            if (!"SUCCESS".equals(status)) {
                return observation(sku, null, failure(status, reply.get("evidence"), sku), status, observedAt, evidence);
            }
            if (!(reply.get("evidence") instanceof String body)) {
                return observation(sku, null, MALFORMED_RESPONSE, status, observedAt, evidence);
            }
            var valid = validate(product, body);
            return observation(sku, valid.count(), valid.status(), status, observedAt, evidence);
        } catch (SecurityException denied) {
            var evidence = latest(session);
            return observation(sku, null, AUTHORIZATION_FAILED, evidence.outcome(), clock.instant(), evidence);
        }
    }

    private static SecureMcpGateway.Evidence latest(SecureMcpGateway.Session session) {
        var events = session.evidence();
        return events.get(events.size() - 1);
    }

    private record Validation(Integer count, InventoryStatus status) {}

    private static Validation validate(ProductCatalog.Product product, String body) {
        try {
            var json = JSON.readTree(body);
            if (json == null || !json.isObject() || json.size() != FIELDS.size()
                    || !FIELDS.stream().allMatch(json::hasNonNull)
                    || !json.path("productId").isTextual() || !json.path("productName").isTextual()) {
                return new Validation(null, MALFORMED_RESPONSE);
            }
            if (!product.sku().equals(json.path("productId").textValue())) {
                return new Validation(null, SKU_MISMATCH);
            }
            if (!product.name().equals(json.path("productName").textValue())
                    || !quantity(json.path("actualInventory"))
                    || !quantity(json.path("expectedInventory")) || !quantity(json.path("reorderThreshold"))) {
                return new Validation(null, MALFORMED_RESPONSE);
            }
            return new Validation(json.path("actualInventory").intValue(), REPORTED);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return new Validation(null, MALFORMED_RESPONSE);
        }
    }

    private static boolean quantity(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0;
    }

    private static InventoryStatus failure(String outcome, Object body, String sku) {
        return switch (outcome) {
            case "TIMEOUT_UNKNOWN" -> TIMEOUT;
            case "DENIED", "AUTHORIZATION_FAILED" -> AUTHORIZATION_FAILED;
            case "MALFORMED_RESPONSE" -> MALFORMED_RESPONSE;
            case "REMOTE_ERROR" -> ("Product not found: " + sku).equals(body instanceof String text ? text.strip() : null)
                    ? NOT_FOUND : REMOTE_ERROR;
            default -> UNAVAILABLE;
        };
    }

    static InventoryObservation notCalled(String sku, String warehouse, InventoryStatus status) {
        return new InventoryObservation(sku, warehouse, null, null, null, null, status, "NOT_CALLED", null, null, null);
    }

    private static InventoryObservation observation(String sku, Integer actual, InventoryStatus status,
            String outcome, Instant instant, SecureMcpGateway.Evidence evidence) {
        return new InventoryObservation(sku, null, actual, null, null, null, status, outcome, instant,
                evidence.server() + "/get_inventory", evidence.requestId());
    }

    static String render(InventoryObservation observation) {
        String description = switch (observation.status()) {
            case REPORTED -> "Product-level actual inventory reported by MCP: " + observation.actualInventory()
                    + " (retrieved " + observation.observedAt() + "). Sellable availability is unknown.";
            case WAREHOUSE_UNSUPPORTED -> "The MCP tool has no warehouse scope for " + observation.warehouseId() + ".";
            case UNKNOWN_WAREHOUSE -> observation.warehouseId() == null
                    ? "The warehouse reference could not be resolved."
                    : "Unknown warehouse " + observation.warehouseId() + ".";
            case NOT_FOUND -> "No inventory record is available for this SKU.";
            case TIMEOUT -> "The inventory lookup timed out.";
            case AUTHORIZATION_FAILED -> "The inventory lookup was not authorized.";
            case MALFORMED_RESPONSE, SKU_MISMATCH -> "The inventory response failed validation.";
            case REMOTE_ERROR, UNAVAILABLE -> "The inventory lookup is unavailable.";
        };
        return observation.sku() + " " + description;
    }
}

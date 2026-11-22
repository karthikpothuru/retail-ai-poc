package com.retail.agent.context;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable JSON-compatible metadata and deterministic serialization, with no additional dependency. */
final class StructuredValues {
    private StructuredValues() {}

    static String content(String value) {
        if (value == null || value.isBlank() || value.length() > 12000) {
            throw new IllegalArgumentException("Content must contain 1 to 12000 characters");
        }
        return value;
    }

    static Map<String, Object> metadata(Map<String, Object> values) {
        @SuppressWarnings("unchecked")
        var snapshot = (Map<String, Object>) freeze(values, 0);
        if (json(snapshot).length() > 16000) {
            throw new IllegalArgumentException("Metadata too large");
        }
        return snapshot;
    }

    private static Object freeze(Object value, int depth) {
        if (depth > 8) { throw new IllegalArgumentException("Metadata nesting too deep"); }
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 64) { throw new IllegalArgumentException("Too many metadata fields"); }
            Map<String, Object> copy = new TreeMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String name) || name.length() > 128) {
                    throw new IllegalArgumentException("Invalid metadata key");
                }
                copy.put(name, freeze(item, depth + 1));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            if (list.size() > 64) { throw new IllegalArgumentException("Too many metadata items"); }
            return list.stream().map(item -> freeze(item, depth + 1)).toList();
        }
        if (value instanceof String string && string.length() <= 12000) { return string; }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof java.math.BigDecimal) { return value; }
        if (value instanceof Double number && Double.isFinite(number)) { return value; }
        throw new IllegalArgumentException("Unsupported metadata value");
    }

    static String json(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().sorted(java.util.Comparator.comparing(entry -> entry.getKey().toString()))
                    .map(entry -> quote(entry.getKey().toString()) + ":" + json(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(StructuredValues::json).collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        if (value instanceof String text) { return quote(text); }
        if (value instanceof Number || value instanceof Boolean) { return value.toString(); }
        throw new IllegalArgumentException("Unsupported JSON value");
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        value.codePoints().forEach(point -> {
            switch (point) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> { if (point < 32) { result.append(String.format("\\u%04x", point)); } else { result.appendCodePoint(point); } }
            }
        });
        return result.append('"').toString();
    }
}

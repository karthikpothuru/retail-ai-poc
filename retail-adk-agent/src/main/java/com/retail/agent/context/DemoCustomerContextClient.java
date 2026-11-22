package com.retail.agent.context;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/** Read-only adapter for Phase 8.1. No arbitrary headers, customer IDs or redirects cross this boundary. */
public final class DemoCustomerContextClient {
    private static final String COOKIE = "RETAIL_DEMO_SESSION";
    private static final String PATH = "/api/v1/demo/session/context";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record SessionCookie(String value) {
        public SessionCookie {
            if (value == null || !value.matches("[A-Za-z0-9_-]{16,128}")) {
                throw new IllegalArgumentException("Invalid demo session cookie");
            }
        }

        @Override
        public String toString() {
            return "DemoSessionCookie[redacted]";
        }
    }

    record Snapshot(
        UUID customerId,
        UUID selectionId,
        boolean personalizationConsent,
        UUID consentEpoch,
        boolean aiProcessingConsent,
        UUID aiProcessingEpoch) {}

    private final URI endpoint;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT).proxy(ProxySelector.of(null)).followRedirects(HttpClient.Redirect.NEVER).build();

    public DemoCustomerContextClient(URI origin) {
        if (!"http".equals(origin.getScheme()) || !"127.0.0.1".equals(origin.getHost())
                || origin.getPort() < 1 || origin.getPort() > 65535 || origin.getUserInfo() != null
                || origin.getRawQuery() != null || origin.getRawFragment() != null
                || !(origin.getRawPath().isEmpty() || "/".equals(origin.getRawPath()))) {
            throw new IllegalArgumentException("Use a literal loopback customer-service origin with a port");
        }
        endpoint = origin.resolve(PATH);
    }

    Optional<Snapshot> resolve(SessionCookie cookie) {
        var request = HttpRequest.newBuilder(endpoint).timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Cookie", COOKIE + "=" + cookie.value())
                .GET().build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 403) {
                return Optional.empty();
            }
            if (response.statusCode() != 200 || response.body().length > 512
                    || !response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0]
                    .equalsIgnoreCase("application/json")) {
                throw unavailable();
            }
            var json = JSON.readTree(response.body());
            if (json == null || !json.isObject()
        || json.size() != 6
        || !json.path("personalizationConsent").isBoolean()
        || !json.path("aiProcessingConsent").isBoolean()) {
    throw unavailable();
}
            return Optional.of(new Snapshot(
        uuid(json, "customerId"),
        uuid(json, "selectionId"),
        json.path("personalizationConsent").booleanValue(),
        uuid(json, "consentEpoch"),
        json.path("aiProcessingConsent").booleanValue(),
        uuid(json, "aiProcessingEpoch")));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | IllegalArgumentException failure) {
            throw unavailable();
        }
    }

    private static UUID uuid(JsonNode json, String field) {
        var node = json.path(field);
        if (!node.isTextual()) {
            throw unavailable();
        }
        var id = UUID.fromString(node.textValue());
        if (!id.toString().equals(node.textValue())) {
            throw unavailable();
        }
        return id;
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Demo customer context is unavailable");
    }
}

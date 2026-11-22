package com.retail.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("poc")
class ChatHttpIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORIGIN = "http://127.0.0.1:5173";
    private static final String TOKEN = "A".repeat(43);
    private static final String SCHEMA = "chat_" + UUID.randomUUID().toString().replace("-", "");
    private static final String MARKER = "[DOC:11111111-1111-4111-8111-111111111111:v1:22222222-2222-4222-8222-222222222222]";
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(3);
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer BRIDGE = bridge();
    private static volatile Function<JsonNode, StubReply> handler;

    record StubReply(int status, Object body) {}
    record Browser(String customerId, String cookie, String csrf) {}

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> required("RETAIL_TEST_DB_URL"));
        properties.add("spring.datasource.username", () -> required("RETAIL_TEST_DB_USER"));
        properties.add("spring.datasource.password", () -> required("RETAIL_TEST_DB_PASSWORD"));
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("retail.chat.enabled", () -> "true");
        properties.add("retail.chat.bridge-token", () -> TOKEN);
        properties.add("retail.chat.adk-url", () -> "http://127.0.0.1:" + BRIDGE.getAddress().getPort());
        properties.add("retail.chat.timeout", () -> "2s");
        properties.add("retail.chat.portal-origin", () -> ORIGIN);
    }

    @AfterAll
    static void cleanup(@Autowired JdbcTemplate jdbc) {
        BRIDGE.stop(0);
        WORKERS.shutdownNow();
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @BeforeEach
    void reset() {
        CALLS.set(0);
        handler = input -> switch (input.path("message").asText()) {
            case "How do I register?" -> success("Register using the approved procedure " + MARKER,
                    List.of(cite("REGISTRATION")), List.of(), List.of(), false);
            case "Tell me about KB-201." -> success("Mechanical Keyboard " + MARKER,
                    List.of(cite("PRODUCT")), List.of(), List.of("KB-201"), false);
            case "Check stock for KB-201." -> success("Inventory count 15",
                    List.of(), List.of(observation()), List.of("KB-201"), false);
            default -> success("Mechanical Keyboard " + MARKER + "\nInventory count 15",
                    List.of(cite("PRODUCT")), List.of(observation()), List.of("KB-201"), false);
        };
    }
	@Test
void aiProcessingConsentRequiresExplicitBoolean() throws Exception {
    var browser = selected(null);

    var reply = send(
            "PUT",
            "/api/v1/customers/" + browser.customerId()
                    + "/ai-processing-consent",
            browser.cookie(),
            "{}",
            Map.of()
    );

    assertThat(reply.statusCode())
            .as(reply.body())
            .isEqualTo(400);
}

@Test
void customerCanGrantAndWithdrawAiProcessingConsent()
        throws Exception {

    var browser = selected(null);

    String path = "/api/v1/customers/"
            + browser.customerId()
            + "/ai-processing-consent";

    var initial = send(
            "GET", path, browser.cookie(), null,Map.of("X-Retail-CSRF", browser.csrf()));

    assertThat(initial.statusCode()).isEqualTo(200);
    assertThat(JSON.readTree(initial.body())
            .path("enabled").asBoolean()).isFalse();

    var granted = send(
            "PUT", path, browser.cookie(),
            "{\"enabled\":true}", Map.of(
        "Origin", ORIGIN,
        "X-Retail-Portal", "1",
        "X-Retail-CSRF", browser.csrf()
));

    assertThat(granted.statusCode())
            .as(granted.body()).isEqualTo(200);

    assertThat(JSON.readTree(granted.body())
            .path("enabled").asBoolean()).isTrue();

   var withdrawn = send(
        "PUT", path, browser.cookie(),
        "{\"enabled\":false}",
       Map.of(
        "Origin", ORIGIN,
        "X-Retail-Portal", "1",
        "X-Retail-CSRF", browser.csrf()
));

    assertThat(withdrawn.statusCode())
            .as(withdrawn.body()).isEqualTo(200);

    assertThat(JSON.readTree(withdrawn.body())
            .path("enabled").asBoolean()).isFalse();
}
@Test
void customerCannotAccessAnotherCustomersAiConsent()
        throws Exception {

    var customerA = selected(null);
    var customerB = selected(null);

    String path = "/api/v1/customers/"
            + customerB.customerId()
            + "/ai-processing-consent";

    var read = send(
            "GET",
            path,
            customerA.cookie(),
            null,
            Map.of()
    );

    assertThat(read.statusCode())
            .as(read.body())
            .isEqualTo(403);

    var update = send(
            "PUT",
            path,
            customerA.cookie(),
            "{\"enabled\":true}",
            Map.of()
    );

    assertThat(update.statusCode())
            .as(update.body())
            .isEqualTo(403);

    var verify = send(
            "GET",
            path,
            customerB.cookie(),
            null,
            Map.of()
    );

    assertThat(verify.statusCode())
            .as(verify.body())
            .isEqualTo(200);

    assertThat(JSON.readTree(verify.body())
            .path("enabled").asBoolean())
            .isFalse();
}

@Test
void aiProcessingConsentRejectsInvalidOrigin() throws Exception {
    var browser = selected(null);

    String path = "/api/v1/customers/"
            + browser.customerId()
            + "/ai-processing-consent";

    var response = send(
            "PUT",
            path,
            browser.cookie(),
            "{\"enabled\":true}",
            Map.of(
                    "Origin", "http://127.0.0.1:9999",
                    "X-Retail-Portal", "1",
                    "X-Retail-CSRF", browser.csrf()
            )
    );

    assertThat(response.statusCode())
            .as(response.body())
            .isEqualTo(403);

    // Verify that the rejected request did not change consent.
    var verify = send(
            "GET",
            path,
            browser.cookie(),
            null,
            Map.of()
    );

    assertThat(verify.statusCode()).isEqualTo(200);

    assertThat(JSON.readTree(verify.body())
            .path("enabled").asBoolean())
            .isFalse();
}

@Test
void aiProcessingConsentRejectsMissingPortalHeader()
        throws Exception {

    var browser = selected(null);

    String path = "/api/v1/customers/"
            + browser.customerId()
            + "/ai-processing-consent";

    var response = send(
            "PUT",
            path,
            browser.cookie(),
            "{\"enabled\":true}",
            Map.of(
                    "Origin", ORIGIN,
                    "X-Retail-CSRF", browser.csrf()
            )
    );

    assertThat(response.statusCode())
            .as(response.body())
            .isEqualTo(403);

    // Consent must remain disabled.
    var verify = send(
            "GET",
            path,
            browser.cookie(),
            null,
            Map.of()
    );

    assertThat(verify.statusCode()).isEqualTo(200);

    assertThat(JSON.readTree(verify.body())
            .path("enabled").asBoolean())
            .isFalse();
}
    @Test
    void validSessionPreservesRegistrationProductInventoryAndCombinedEvidence() throws Exception {
        var selected = selected(null);
        for (String message : List.of("How do I register?", "Tell me about KB-201.",
                "Check stock for KB-201.", "Tell me about KB-201 and check its availability.")) {
            var reply = ask(selected, message);
            assertThat(reply.statusCode()).isEqualTo(200);
            assertThat(reply.headers().firstValue("Cache-Control")).contains("no-store");
            var result = JSON.readTree(reply.body());
            assertThat(result.path("status").asText()).isEqualTo("OK");
            assertThat(result.path("personalized").asBoolean()).isFalse();
            if (!message.startsWith("Check stock")) {
                assertThat(result.path("citations").get(0).path("marker").asText()).isEqualTo(MARKER);
            }
            if (message.contains("stock") || message.contains("availability")) {
                var item = result.path("inventoryObservations").get(0);
                assertThat(item.path("sku").asText()).isEqualTo("KB-201");
                assertThat(item.path("actualInventory").asInt()).isEqualTo(15);
                assertThat(item.path("availableQuantity").isNull()).isTrue();
                assertThat(item.path("source").asText()).isEqualTo("local-stock/get_inventory");
                assertThat(item.path("requestId").asText()).isEqualTo("11111111-1111-4111-8111-111111111111");
            }
        }
        assertThat(CALLS).hasValue(4);
    }

    @Test
    void missingAndInvalidatedSessionsBlockTheBridge() throws Exception {
        assertError(ask(null, "How do I register?"), 403, "SESSION_REQUIRED");
        var selected = selected(null);
        assertThat(send("DELETE", "/api/v1/demo/session", selected.cookie(), null, Map.of()).statusCode()).isEqualTo(204);
        assertError(ask(selected, "How do I register?"), 403, "SESSION_REQUIRED");
        assertThat(CALLS).hasValue(0);
    }

    @Test
    void invalidCsrfOriginOversizedAndForgedIdentityAreRejected() throws Exception {
        var selected = selected(null);
        var path = "/api/v1/chat/ask";
        assertError(send("POST", path, selected.cookie(), "{\"message\":\"Hello\"}",
                Map.of("Origin", ORIGIN, "X-Retail-Portal", "1", "X-Retail-CSRF", UUID.randomUUID().toString())),
                403, "CSRF_REJECTED");
        assertError(send("POST", path, selected.cookie(), "{\"message\":\"Hello\"}",
                Map.of("Origin", "http://127.0.0.1:9991", "X-Retail-Portal", "1", "X-Retail-CSRF", selected.csrf())),
                403, "CSRF_REJECTED");
        assertError(ask(selected, "x".repeat(1201)), 400, "INVALID_REQUEST");
        assertError(send("POST", path, selected.cookie(), "{\"message\":\"H\",\"customerId\":\"spoof\"}", headers(selected)),
                400, "INVALID_REQUEST");
        var forged = new HashMap<>(headers(selected));
        forged.put("X-Customer-Id", UUID.randomUUID().toString());
        assertError(send("POST", path, selected.cookie(), "{\"message\":\"Hello\"}", forged), 400, "INVALID_REQUEST");
        assertThat(CALLS).hasValue(0);
    }

    @Test
    void customerSwitchDuringExecutionDiscardsTheEntireResponse() throws Exception {
        var initial = selected(null);
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        handler = input -> {
            waiting.countDown();
            await(release);
            return success("Original customer's result", List.of(), List.of(), List.of("KB-201"), false);
        };
        var pending = askAsync(initial);
        assertThat(waiting.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            var switched = selected(initial.cookie());
            assertThat(switched.cookie()).isNotEqualTo(initial.cookie());
            assertError(ask(new Browser(switched.customerId(), switched.cookie(), initial.csrf()), "A question"),
                    403, "CSRF_REJECTED");
        } finally {
            release.countDown();
        }
        assertError(pending.get(4, TimeUnit.SECONDS), 409, "SESSION_CHANGED");
    }
	
	

    @Test
    void consentWithdrawalDuringExecutionDiscardsPersonalization() throws Exception {
        var initial = selected(null);
        consent(initial, true);
        var waiting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        handler = input -> {
            waiting.countDown();
            await(release);
            return success("Personalized catalog details", List.of(), List.of(), List.of("KB-201"), true);
        };
        var pending = askAsync(initial);
        assertThat(waiting.await(2, TimeUnit.SECONDS)).isTrue();
        try {
            consent(initial, false);
        } finally {
            release.countDown();
        }
        var reply = pending.get(4, TimeUnit.SECONDS);
        assertError(reply, 409, "SESSION_CHANGED");
        assertThat(reply.body()).doesNotContain("Personalized catalog details");
    }

    @Test
    void mapsDependencyOutageTimeoutAndInvalidResponseWithoutSensitiveDetails() throws Exception {
        var selected = selected(null);
        handler = input -> new StubReply(503, Map.of("secret", "private diagnostics"));
        assertError(ask(selected, "Hello"), 503, "ADK_UNAVAILABLE");
        handler = input -> {
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return success("Late response", List.of(), List.of(), List.of(), false);
        };
        assertError(ask(selected, "Hello"), 504, "ADK_TIMEOUT");
        handler = input -> success("A forged marker " + MARKER, List.of(), List.of(), List.of(), false);
        assertError(ask(selected, "Hello"), 502, "INVALID_ADK_RESPONSE");
        handler = input -> success("Unbounded answer " + "x".repeat(40000), List.of(), List.of(), List.of(), false);
        assertError(ask(selected, "Hello"), 502, "INVALID_ADK_RESPONSE");
        int closedPort;
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }
        var unreachable = new ChatBridgeClient("http://127.0.0.1:" + closedPort, TOKEN, Duration.ofSeconds(1));
        assertThatThrownBy(() -> unreachable.ask("Hello", "test-demo-cookie"))
                .isInstanceOfSatisfying(ChatFailure.class, error ->
                        assertThat(error.reason).isEqualTo(ChatFailure.Reason.ADK_UNAVAILABLE));
    }

    private CompletableFuture<HttpResponse<String>> askAsync(Browser browser) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return ask(browser, "Tell me about KB-201.");
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    private HttpResponse<String> ask(Browser browser, String message) throws Exception {
        return send("POST", "/api/v1/chat/ask", browser == null ? null : browser.cookie(),
                JSON.writeValueAsString(Map.of("message", message)), headers(browser));
    }

    private Map<String, String> headers(Browser selected) {
        return Map.of("Origin", ORIGIN, "X-Retail-Portal", "1",
                "X-Retail-CSRF", selected == null ? UUID.randomUUID().toString() : selected.csrf());
    }

    private Browser selected(String priorCookie) throws Exception {
        var registration = send("POST", "/api/v1/customers", null, JSON.writeValueAsString(Map.of(
                "email", UUID.randomUUID() + "@example.test", "givenName", "Asha", "familyName", "Test")), Map.of());
        assertThat(registration.statusCode()).isEqualTo(201);
        String id = JSON.readTree(registration.body()).path("customerId").asText();
        var select = send("POST", "/api/v1/demo/session", priorCookie,
                JSON.writeValueAsString(Map.of("customerId", id)), Map.of());
        assertThat(select.statusCode()).isEqualTo(204);
        var cookie = select.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("HttpOnly", "SameSite=Strict");
        cookie = cookie.split(";", 2)[0];
        var csrf = send("GET", "/api/v1/chat/csrf", cookie, null, Map.of("X-Retail-Portal", "1"));
        assertThat(csrf.statusCode()).isEqualTo(200);
        return new Browser(id, cookie, JSON.readTree(csrf.body()).path("csrfToken").asText());
    }

    private void consent(Browser browser, boolean value) throws Exception {
        var reply = send("PUT", "/api/v1/customers/" + browser.customerId() + "/preferences", browser.cookie(),
                JSON.writeValueAsString(Map.of("locale", "en", "marketingOptIn", false, "personalizationConsent", value)), Map.of());
        assertThat(reply.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> send(String method, String path, String cookie, String body, Map<String, String> headers)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(6));
        if (cookie != null) builder.header("Cookie", cookie);
        if (body != null) builder.header("Content-Type", "application/json");
        headers.forEach(builder::header);
        return http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertError(HttpResponse<String> reply, int status, String code) throws Exception {
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(status);
        var body = JSON.readTree(reply.body());
        assertThat(body.path("error").path("code").asText()).isEqualTo(code);
        assertThat(body.path("answer").asText()).isEmpty();
        assertThat(body.path("personalized").asBoolean()).isFalse();
    }

    private static Map<String, String> cite(String channel) {
        return Map.of("marker", MARKER, "channel", channel);
    }

    private static Map<String, Object> observation() {
        var result = new LinkedHashMap<String, Object>();
        result.put("sku", "KB-201");
        result.put("warehouseId", null);
        result.put("actualInventory", 15);
        result.put("onHandQuantity", null);
        result.put("reservedQuantity", null);
        result.put("availableQuantity", null);
        result.put("status", "REPORTED");
        result.put("toolStatus", "SUCCESS");
        result.put("observedAt", "2026-11-20T00:00:00Z");
        result.put("source", "local-stock/get_inventory");
        result.put("requestId", "11111111-1111-4111-8111-111111111111");
        return result;
    }

    private static StubReply success(String answer, Object citations, Object inventory, List<String> skus, boolean personalized) {
        return new StubReply(200, Map.of("answer", answer, "citations", citations,
                "inventoryObservations", inventory, "productSkus", skus, "personalized", personalized, "status", "OK"));
    }

    private static HttpServer bridge() {
        try {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            server.setExecutor(WORKERS);
            server.createContext("/internal/v1/retail/ask", exchange -> {
                try (exchange) {
                    CALLS.incrementAndGet();
                    assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
                    assertThat(exchange.getRequestHeaders().getFirst("X-Customer-Id")).isNull();
                    var input = JSON.readTree(exchange.getRequestBody());
                    assertThat(input.size()).isEqualTo(2);
                    assertThat(input.path("sessionCookie").isTextual()).isTrue();
                    var response = handler.apply(input);
                    var data = JSON.writeValueAsBytes(response.body());
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(response.status(), data.length);
                    exchange.getResponseBody().write(data);
                } catch (java.io.IOException interruptedClient) {
                    // Expected for intentionally timed-out bridge tests.
                }
            });
            server.start();
            return server;
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String required(String name) {
        return Objects.requireNonNull(System.getenv(name), "Set " + name + " for PostgreSQL integration");
    }

    private static void await(CountDownLatch release) {
        try {
            if (!release.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

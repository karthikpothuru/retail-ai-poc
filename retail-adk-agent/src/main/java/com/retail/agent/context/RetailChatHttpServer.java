package com.retail.agent.context;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Function;

/** Loopback-only bridge for the existing single-turn retail orchestration. */
public final class RetailChatHttpServer implements AutoCloseable {
    private static final String ROUTE = "/internal/v1/retail/ask";
    private static final int MAX_REQUEST = 8192;
    private static final int MAX_REPLY = 32768;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .registerModule(new SimpleModule().addSerializer(Instant.class, ToStringSerializer.instance));

    @FunctionalInterface
    interface Turn {
        PersonalizedRetailAgent.Result answer(DemoCustomerContextClient.SessionCookie cookie, String message);
    }

    private record Reply(String answer, List<PersonalizedRetailAgent.Citation> citations,
                         List<ProductKnowledgeResponse.InventoryObservation> inventoryObservations,
                         List<String> productSkus, boolean personalized, String status) {}
						 
    record ShadowRequest(
        String message,
        java.util.function.BooleanSupplier authorized
) {}

    private final HttpServer server;
    private final ExecutorService requests = pool(6, 12);
    private final ExecutorService turns = pool(3, 3);
    private final byte[] authorization;
    private final long timeoutMillis;
    private final Function<DemoCustomerContextClient.SessionCookie, Optional<DemoCustomerContextClient.Snapshot>> sessions;
    private final Turn turn;
	private final java.util.function.Consumer<ShadowRequest> shadowObserver;

 
RetailChatHttpServer(
        int port,
        String bridgeToken,
        Duration timeout,
        Function<DemoCustomerContextClient.SessionCookie,
                Optional<DemoCustomerContextClient.Snapshot>> sessions,
        Turn turn) throws IOException {

    this(
            port,
            bridgeToken,
            timeout,
            sessions,
            turn,
            message -> {}
    );
}

	
	
RetailChatHttpServer(
        int port,
        String bridgeToken,
        Duration timeout,
        Function<DemoCustomerContextClient.SessionCookie,
                Optional<DemoCustomerContextClient.Snapshot>> sessions,
        Turn turn,
        java.util.function.Consumer<ShadowRequest> shadowObserver) throws IOException {

    if (!bridgeToken.matches("[A-Za-z0-9_-]{43}")
            || timeout.toMillis() < 1
            || timeout.toSeconds() > 120) {
        throw new IllegalArgumentException(
                "Invalid loopback chat bridge configuration");
    }

    authorization = ("Bearer " + bridgeToken)
            .getBytes(StandardCharsets.UTF_8);

    timeoutMillis = timeout.toMillis();
    this.sessions = sessions;
    this.turn = turn;
    this.shadowObserver = java.util.Objects.requireNonNull(shadowObserver);

    server = HttpServer.create(
            new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), port), 16);

    server.setExecutor(requests);
    server.createContext(ROUTE, this::handle);
}


    private static ExecutorService pool(int threads, int queue) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue),
                new ThreadPoolExecutor.AbortPolicy());
    }

    void start() { server.start(); }

    int port() { return server.getAddress().getPort(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().toString().equals(ROUTE) || !exchange.getRequestMethod().equals("POST")) {
                error(exchange, 404, "NOT_FOUND");
                return;
            }
            var headers = exchange.getRequestHeaders();
            var tokens = headers.get("Authorization");
            if (tokens == null || tokens.size() != 1
                    || !MessageDigest.isEqual(authorization, tokens.get(0).getBytes(StandardCharsets.UTF_8))) {
                error(exchange, 401, "BRIDGE_UNAUTHORIZED");
                return;
            }
            if (headers.containsKey("X-Customer-Id") || headers.containsKey("X-Memory-Namespace")
                    || headers.getFirst("Origin") != null
                    || !isJson(headers.getFirst("Content-Type"))) {
                error(exchange, 400, "INVALID_REQUEST");
                return;
            }

            String message;
            DemoCustomerContextClient.SessionCookie cookie;
            try {
                var bytes = exchange.getRequestBody().readNBytes(MAX_REQUEST + 1);
                var data = bytes.length <= MAX_REQUEST ? JSON.readTree(bytes) : null;
                if (data == null || !data.isObject() || data.size() != 2 || !data.path("message").isTextual()
                        || !data.path("sessionCookie").isTextual()) {
                    error(exchange, 400, "INVALID_REQUEST");
                    return;
                }
                message = data.path("message").textValue().strip();
                if (message.isBlank() || message.length() > 1200) {
                    error(exchange, 400, "INVALID_REQUEST");
                    return;
                }
                cookie = new DemoCustomerContextClient.SessionCookie(data.path("sessionCookie").textValue());
            } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException malformed) {
                error(exchange, 400, "INVALID_REQUEST");
                return;
            }

            Future<Reply> future;
            try {
                future = turns.submit(() -> reply(cookie, message));
            } catch (RejectedExecutionException busy) {
                error(exchange, 503, "ADK_BUSY");
                return;
            }
            try {
                var bytes = JSON.writeValueAsBytes(future.get(timeoutMillis, TimeUnit.MILLISECONDS));
                if (bytes.length > MAX_REPLY) {
                    error(exchange, 502, "INVALID_RESPONSE");
                } else {
                    send(exchange, 200, bytes);
                }
            } catch (TimeoutException expired) {
                future.cancel(true);
                error(exchange, 504, "ADK_TIMEOUT");
            } catch (InterruptedException interrupted) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                error(exchange, 503, "ADK_UNAVAILABLE");
            } catch (ExecutionException failed) {
               Throwable cause = failed.getCause();

    if (cause instanceof SessionFailure session) {
        error(exchange, session.changed ? 409 : 403,
                session.changed ? "SESSION_CHANGED" : "SESSION_REQUIRED");
    } else {
        System.err.println(
                "[ADK-DIAGNOSTIC] Agent execution failed: "
                + cause.getClass().getName()
        );

        // Local development diagnostics only.
        // Review output for sensitive data before sharing.
        cause.printStackTrace(System.err);

        error(exchange, 503, "ADK_UNAVAILABLE");
    }
            }
        }
    }

    private Reply reply(DemoCustomerContextClient.SessionCookie cookie, String message) {
        var before = sessions.apply(cookie).orElseThrow(() -> new SessionFailure(false));
        var conversational = ConversationalIntent.match(message.replace(cookie.value(), "[session removed]"));
        var result = conversational.map(ConversationalIntent::result)
                .orElseGet(() -> turn.answer(cookie, message));
        var after = sessions.apply(cookie).orElseThrow(() -> new SessionFailure(true));
        if (!before.customerId().equals(after.customerId()) || !before.selectionId().equals(after.selectionId())
                || result.personalized() && (!before.personalizationConsent() || !after.personalizationConsent()
                || !before.consentEpoch().equals(after.consentEpoch()))) {
            throw new SessionFailure(true);
        }
        if (conversational.isPresent()) {
            return new Reply(result.answer(), List.of(), List.of(), List.of(), false, "OK");
        }
        var inventory = result.product().stream().flatMap(product -> product.liveInventoryBySku().values().stream())
                .sorted(java.util.Comparator.comparing(ProductKnowledgeResponse.InventoryObservation::sku)).toList();
        var skus = result.product().stream().flatMap(product -> product.products().stream())
                .map(ProductCatalog.Product::sku).distinct().toList();
        boolean supported = result.capabilitiesResponse()
        || !result.citations().isEmpty()
        || result.product()
            .filter(p -> p.outcome()
                    == ProductKnowledgeResponse.Outcome.FOUND)
            .isPresent();
        boolean incomplete = result.registration().isPresent()
                && result.citations().stream().noneMatch(c -> c.channel().equals("REGISTRATION"));
        incomplete |= result.product().filter(p -> p.outcome() != ProductKnowledgeResponse.Outcome.FOUND
                || p.answer().contains(DocumentGrounding.insufficient()) || p.answer().contains("temporarily unavailable")).isPresent();
        incomplete |= inventory.stream().anyMatch(item -> item.status() != ProductKnowledgeResponse.InventoryStatus.REPORTED);
       
String status = supported
        ? (incomplete ? "PARTIAL" : "OK")
        : "INSUFFICIENT";

Reply reply = new Reply(
        result.answer(),
        result.citations(),
        inventory,
        skus,
        result.personalized(),
        status
);

// Shadow classification is non-authoritative.
// It must not change or fail the customer response.
// Shadow classification is non-authoritative.
// Gemini must not receive customer content without explicit AI consent.
if (before.aiProcessingConsent()
        && after.aiProcessingConsent()
        && before.aiProcessingEpoch().equals(after.aiProcessingEpoch())) {

    // Revalidate consent immediately before scheduling shadow processing.
    try {
        var current = sessions.apply(cookie)
                .orElseThrow(() -> new SessionFailure(true));

        if (after.customerId().equals(current.customerId())
                && after.selectionId().equals(current.selectionId())
                && current.aiProcessingConsent()
                && after.aiProcessingEpoch().equals(current.aiProcessingEpoch())) {

            shadowObserver.accept(new ShadowRequest(
        message,
        () -> {
            try {
                var latest = sessions.apply(cookie).orElse(null);

                return latest != null
                        && after.customerId().equals(latest.customerId())
                        && after.selectionId().equals(latest.selectionId())
                        && latest.aiProcessingConsent()
                        && after.aiProcessingEpoch()
                                .equals(latest.aiProcessingEpoch());

            } catch (RuntimeException unavailable) {
                return false;
            }
        }
));
        }
    } catch (RuntimeException shadowFailure) {
        System.err.println(
                "[ADK-INTENT] shadow processing skipped: "
                        + shadowFailure.getClass().getSimpleName());
    }
}

return reply;

    }

    private static final class SessionFailure extends RuntimeException {
        private final boolean changed;
        private SessionFailure(boolean changed) { this.changed = changed; }
    }

    private static boolean isJson(String value) {
        return value != null && value.split(";", 2)[0].strip().equalsIgnoreCase("application/json");
    }

    private static void error(HttpExchange exchange, int status, String code) throws IOException {
        send(exchange, status, JSON.writeValueAsBytes(Map.of("code", code)));
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        server.stop(0);
        turns.shutdownNow();
        requests.shutdownNow();
    }

  
public static void main(String[] args) throws Exception {

    String customerOrigin = System.getenv().getOrDefault(
            "RETAIL_CUSTOMER_CONTEXT_URL",
            "http://127.0.0.1:8081");

    int port = Integer.parseInt(
            System.getenv().getOrDefault(
                    "RETAIL_CHAT_ADK_PORT", "8091"));

    long timeout = Long.parseLong(
            System.getenv().getOrDefault(
                    "RETAIL_CHAT_ADK_DEADLINE_SECONDS", "45"));

    String token = System.getenv().getOrDefault(
            "RETAIL_CHAT_BRIDGE_TOKEN", "");

    // Declare the feature flag before the lambda.
    boolean geminiIntentShadow = "true".equalsIgnoreCase(
            System.getenv().getOrDefault(
                    "RETAIL_GEMINI_INTENT_SHADOW", "false"));

    System.out.println(
            "[ADK-INTENT] Gemini shadow mode enabled: "
                    + geminiIntentShadow);

    var context = new DemoCustomerContextClient(
            URI.create(customerOrigin));

    try (var shadow = new RetailIntentShadow();
         var server = new RetailChatHttpServer(
                 port,
                 token,
                 Duration.ofSeconds(timeout),
                 context::resolve,
                 
(cookie, message) ->
        PersonalizedRetailCliRunner.withAgent(
                cookie, agent -> agent.answer(message)),
request -> {
    if (geminiIntentShadow) {
        shadow.observe(
                request.message(),
                request.authorized()
        );
    }
}
)) {

        server.start();

        System.out.println(
                "Retail ADK chat bridge listening on 127.0.0.1:"
                        + server.port());

        new CountDownLatch(1).await();
    }
}

}

package com.retail.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.retail.agent.context.ProductKnowledgeAgentTest.*;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "runChatAdkIntegrationTests", matches = "true")
class RetailChatEndToEndTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI spring = URI.create(System.getenv("RETAIL_CUSTOMER_CONTEXT_URL"));
    private final DemoCustomerContextClient context = new DemoCustomerContextClient(spring);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Test
    void springAndRealAdkBridgeShareTheSessionAndPreserveEachEvidenceChannel() throws Exception {
        var documents = new InMemoryDocumentRepository();
        var vectors = new FakeEmbeddingProvider(768);
        var registration = new RegistrationKnowledgeAgent(RegistrationKnowledgeAgentTest.CORPUS, documents,
                vectors, (event, access) -> {}, CLOCK);
        registration.publish(RegistrationKnowledgeAgent.Topic.REGISTRATION, 0);
        var product = new ProductKnowledgeAgent(access -> fixtures(), CORPUS, documents, vectors, (event, access) -> {},
                CLOCK, model(selection(KEYBOARD, KEYBOARD_LAYOUT)));
        product.publish(KEYBOARD, 0);
        var memory = new ConsentAwareCustomerMemory(context, new InMemoryMemoryRepository(CLOCK,
                ConsentAwareCustomerMemory.writePolicy(CLOCK), new ContextAuditLog(CLOCK)), (event, access) -> {}, CLOCK);
        var transport = new ProductInventoryIntegrationTest.FakeSpring();
        try (var inventory = new ProductInventoryHost(transport, new byte[32], (event, access) -> {}, event -> {},
                CLOCK, Duration.ofSeconds(2));
             var bridge = new RetailChatHttpServer(Integer.parseInt(System.getenv("RETAIL_CHAT_ADK_PORT")),
                System.getenv("RETAIL_CHAT_BRIDGE_TOKEN"), Duration.ofSeconds(15), context::resolve,
                (cookie, message) -> new PersonalizedRetailAgent(registration, product, memory, cookie, inventory).answer(message))) {
            bridge.start();
            var registered = send("POST", "/api/v1/customers", null, Map.of("email", UUID.randomUUID() + "@example.test",
                    "givenName", "Asha", "familyName", "Demo"), null);
            assertThat(registered.statusCode()).isEqualTo(201);
            var id = JSON.readTree(registered.body()).path("customerId").asText();
            var selected = send("POST", "/api/v1/demo/session", null, Map.of("customerId", id), null);
            assertThat(selected.statusCode()).isEqualTo(204);
            String cookie = selected.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            try {
                var csrfReply = send("GET", "/api/v1/chat/csrf", cookie, null, null);
                assertThat(csrfReply.statusCode()).isEqualTo(200);
                String csrf = JSON.readTree(csrfReply.body()).path("csrfToken").asText();
                String question = "How do I register? Tell me about KB-201 and check its availability.";
                var consent = send("PUT", "/api/v1/customers/" + id + "/preferences", cookie,
                        Map.of("locale", "en", "marketingOptIn", false, "personalizationConsent", true), null);
                assertThat(consent.statusCode()).isEqualTo(200);
                var opaque = new DemoCustomerContextClient.SessionCookie(cookie.substring("RETAIL_DEMO_SESSION=".length()));
                assertThat(memory.remember(opaque, ConsentAwareCustomerMemory.DetailLevel.DETAILED).status())
                        .isEqualTo(ConsentAwareCustomerMemory.Status.READY);
                var response = send("POST", "/api/v1/chat/ask", cookie, Map.of("message", question), csrf);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                var reply = JSON.readTree(response.body());
                assertThat(reply.path("status").asText()).isEqualTo("OK");
                assertThat(reply.path("personalized").asBoolean()).isTrue();
                assertThat(reply.path("answer").asText()).contains("Catalog details", "HTTP 201", marker(KEYBOARD));
                assertThat(reply.path("citations").size()).isEqualTo(2);
                var stock = reply.path("inventoryObservations").get(0);
                assertThat(stock.path("source").asText()).isEqualTo("local-stock/get_inventory");
                assertThat(stock.path("actualInventory").asInt()).isEqualTo(15);
                assertThat(stock.path("availableQuantity").isNull()).isTrue();
            } finally {
                send("DELETE", "/api/v1/demo/session", cookie, null, null);
            }
        }
    }

    private HttpResponse<String> send(String method, String path, String cookie, Object body, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(spring.resolve(path)).timeout(Duration.ofSeconds(20))
                .header("X-Retail-Portal", "1");
        if (cookie != null) request.header("Cookie", cookie);
        if (body != null) request.header("Content-Type", "application/json");
        if (csrf != null) request.header("Origin", "http://127.0.0.1:5173").header("X-Retail-CSRF", csrf);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

package com.retail.agent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;

import static com.retail.agent.context.ProductKnowledgeResponse.InventoryStatus.*;
import static com.retail.agent.context.ProductKnowledgeAgentTest.*;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "runProductLiveMcpTests", matches = "true")
class ProductInventoryLiveTest {
    @Test
    void combinesRealSpringInventoryWithReviewedProductCitations() throws Exception {
        var model = model(selections(KEYBOARD, KEYBOARD_LAYOUT, MONITOR, MONITOR_INPUT));
        var agent = agent(model);
        agent.publish(KEYBOARD, 0);
        agent.publish(MONITOR, 0);
        try (var host = ProductInventoryHost.fromEnvironment((event, access) -> {}, CLOCK)) {
            var result = host.answer(agent, "Compare KB-201 and MN-301 and show their stock.");
            assertThat(result.answer()).contains(marker(KEYBOARD), marker(MONITOR));
            assertThat(result.liveInventoryBySku().get(KEYBOARD).status()).isEqualTo(REPORTED);
            assertThat(result.liveInventoryBySku().get(KEYBOARD).actualInventory()).isEqualTo(15);
            assertThat(result.liveInventoryBySku().get(MONITOR).actualInventory()).isEqualTo(55);
            var central = host.answer(agent, "Is WM-101 available in the central warehouse?");
            assertThat(central.liveInventoryBySku().get(MOUSE).status()).isEqualTo(WAREHOUSE_UNSUPPORTED);
        }
        assertThat(model.requests).hasSize(1);
    }

    @Test
    void realSpringRejectsIncorrectDelegationSignature() {
        var audit = new ArrayList<SecureMcpGateway.Audit>();
        var endpoint = URI.create(System.getenv("MCP_SERVER_URL"));
        assertThat(endpoint.getHost()).isEqualTo("127.0.0.1");
        // The test server generates its own key. This client deliberately uses a different test key.
        try (var host = new ProductInventoryHost(new SdkMcpTransport(endpoint), new byte[32], (event, access) -> {},
                audit::add, CLOCK, Duration.ofSeconds(5))) {
            var result = host.answer(agent(model()), "Check stock for WM-101.");
            assertThat(result.liveInventoryBySku().get(MOUSE).status()).isEqualTo(AUTHORIZATION_FAILED);
            assertThat(result.liveInventoryBySku().get(MOUSE).actualInventory()).isNull();
            assertThat(audit).extracting(SecureMcpGateway.Audit::outcome).containsExactly("AUTHORIZATION_FAILED");
        }
    }

    @Test
    void singleTurnServesLiveInventoryWithoutADemoCustomerCookie() {
        var registration = new RegistrationKnowledgeAgent(RegistrationKnowledgeAgentTest.CORPUS,
                new InMemoryDocumentRepository(), new FakeEmbeddingProvider(768), (event, access) -> {}, CLOCK);
        try (var host = ProductInventoryHost.fromEnvironment((event, access) -> {}, CLOCK)) {
            var retail = new PersonalizedRetailAgent(registration, agent(model()), null, null, host);
            assertThat(PersonalizedRetailCliRunner.run(retail, "Check stock for WM-101."))
                    .contains("reported by MCP: 105", "Sellable availability is unknown").doesNotContain("[DOC:");
        }
    }

    @Test
    void chatBridgePreservesActualSecuredMcpProvenance() throws Exception {
        var snapshot = ConsentAwareCustomerMemoryTest.synthetic(false);
        var registration = new RegistrationKnowledgeAgent(RegistrationKnowledgeAgentTest.CORPUS,
                new InMemoryDocumentRepository(), new FakeEmbeddingProvider(768), (event, access) -> {}, CLOCK);
        var products = agent(model());
        try (var host = ProductInventoryHost.fromEnvironment((event, access) -> {}, CLOCK);
             var bridge = new RetailChatHttpServer(0, RetailChatHttpServerTest.TOKEN, Duration.ofSeconds(12),
                     cookie -> java.util.Optional.of(snapshot), (cookie, question) ->
                     new PersonalizedRetailAgent(registration, products, null, cookie, host).answer(question))) {
            bridge.start();
            var request = java.net.http.HttpRequest.newBuilder(URI.create(
                            "http://127.0.0.1:" + bridge.port() + "/internal/v1/retail/ask"))
                    .header("Authorization", "Bearer " + RetailChatHttpServerTest.TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                            "{\"message\":\"Check stock for WM-101.\",\"sessionCookie\":\"valid-opaque-session-cookie\"}"))
                    .build();
            var response = java.net.http.HttpClient.newHttpClient().send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var result = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body());
            var observation = result.path("inventoryObservations").get(0);
            assertThat(observation.path("actualInventory").asInt()).isEqualTo(105);
            assertThat(observation.path("source").asText()).isEqualTo("local-stock/get_inventory");
            assertThat(observation.path("requestId").asText()).isNotBlank();
            assertThat(observation.path("availableQuantity").isNull()).isTrue();
            assertThat(result.path("citations").isEmpty()).isTrue();
        }
    }

    private static ProductKnowledgeAgent agent(RuntimeScriptedModel model) {
        return new ProductKnowledgeAgent(access -> fixtures(), CORPUS, new InMemoryDocumentRepository(),
                new FakeEmbeddingProvider(768), (event, access) -> {}, CLOCK, model);
    }
}

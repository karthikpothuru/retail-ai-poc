package com.retail.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.retail.agent.context.ConsentAwareCustomerMemory.DetailLevel.CONCISE;
import static com.retail.agent.context.ConsentAwareCustomerMemory.Status.*;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "runCustomerContextHttpTests", matches = "true")
class DemoCustomerContextHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final URI origin = URI.create(System.getenv("RETAIL_CUSTOMER_CONTEXT_URL"));
    private final DemoCustomerContextClient context = new DemoCustomerContextClient(origin);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void crossesTheRealCustomerSessionBoundaryWithoutAcceptingAnIdentityFromAdk() throws Exception {
        var clock = Clock.systemUTC();
        var memory = new ConsentAwareCustomerMemory(context, new InMemoryMemoryRepository(clock,
                ConsentAwareCustomerMemory.writePolicy(clock), new ContextAuditLog(clock)), (event, access) -> {}, clock);
        var first = register();
        var second = register();
        var initial = select(first, null);
        try {
            var spoof = send("GET", "/api/v1/demo/session/context?customerId=" + second, initial, null, second.toString());
            assertThat(spoof.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(spoof.body()).path("customerId").asText()).isEqualTo(first.toString());
            assertThat(send("GET", "/api/v1/demo/session/context", null, null, second.toString()).statusCode()).isEqualTo(403);
            assertThat(memory.remember(initial, CONCISE).status()).isEqualTo(CONSENT_DISABLED);
            consent(first, initial, true);
            assertThat(memory.remember(initial, CONCISE).status()).isEqualTo(READY);
            assertThat(memory.read(initial).memory()).hasSize(1);
            consent(first, initial, false);
            assertThat(memory.read(initial).status()).isEqualTo(CONSENT_DISABLED);
            consent(first, initial, true);
            assertThat(memory.read(initial).memory()).isEmpty();
            assertThat(memory.remember(initial, CONCISE).status()).isEqualTo(READY);

            var switched = select(second, initial);
            try {
                assertThat(memory.read(initial).status()).isEqualTo(NO_SESSION);
                consent(second, switched, true);
                assertThat(memory.read(switched).memory()).isEmpty();
            } finally {
                invalidate(switched);
            }
            assertThat(memory.read(switched).status()).isEqualTo(NO_SESSION);
        } finally {
            invalidate(initial);
        }
    }

    private UUID register() throws Exception {
        var result = send("POST", "/api/v1/customers", null, Map.of(
                "email", UUID.randomUUID() + "@example.test", "givenName", "Asha", "familyName", "Rao"), null);
        assertThat(result.statusCode()).isEqualTo(201);
        return UUID.fromString(JSON.readTree(result.body()).path("customerId").asText());
    }

    private DemoCustomerContextClient.SessionCookie select(UUID id, DemoCustomerContextClient.SessionCookie current)
            throws Exception {
        var result = send("POST", "/api/v1/demo/session", current, Map.of("customerId", id), null);
        assertThat(result.statusCode()).isEqualTo(204);
        String token = result.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        return new DemoCustomerContextClient.SessionCookie(token.substring("RETAIL_DEMO_SESSION=".length()));
    }

    private void consent(UUID id, DemoCustomerContextClient.SessionCookie cookie, boolean state) throws Exception {
        var result = send("PUT", "/api/v1/customers/" + id + "/preferences", cookie,
                Map.of("locale", "en", "marketingOptIn", false, "personalizationConsent", state), null);
        assertThat(result.statusCode()).isEqualTo(200);
    }

    private void invalidate(DemoCustomerContextClient.SessionCookie cookie) throws Exception {
        assertThat(send("DELETE", "/api/v1/demo/session", cookie, null, null).statusCode()).isEqualTo(204);
    }

    private HttpResponse<String> send(String method, String path, DemoCustomerContextClient.SessionCookie cookie,
                                    Map<String, ?> body, String spoof) throws Exception {
        var builder = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(5));
        if (cookie != null) {
            builder.header("Cookie", "RETAIL_DEMO_SESSION=" + cookie.value());
        }
        if (spoof != null) {
            builder.header("X-Customer-Id", spoof);
        }
        var publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            builder.header("Content-Type", "application/json");
            publisher = HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        }
        return http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }
}

package com.retail.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RetailChatHttpServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String TOKEN = "A".repeat(43);
    private static final String COOKIE = "valid-opaque-session-cookie";
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void requiresBridgeSecretAndRejectsForgedIdentityEvenWithTheSecret() throws Exception {
        var calls = new AtomicInteger();
        var active = ConsentAwareCustomerMemoryTest.synthetic(false);
        try (var server = new RetailChatHttpServer(0, TOKEN, Duration.ofSeconds(2), cookie -> Optional.of(active),
                (cookie, question) -> { calls.incrementAndGet(); return answer(false); })) {
            server.start();
            assertThat(send(server, null, Map.of()).statusCode()).isEqualTo(401);
            assertThat(send(server, "B".repeat(43), Map.of()).statusCode()).isEqualTo(401);
            assertThat(send(server, TOKEN, Map.of("X-Customer-Id", UUID.randomUUID().toString())).statusCode()).isEqualTo(400);
            assertThat(send(server, TOKEN, Map.of("Origin", "http://127.0.0.1:5173")).statusCode()).isEqualTo(400);
            assertThat(calls).hasValue(0);
            assertThat(send(server, TOKEN, Map.of()).statusCode()).isEqualTo(200);
            assertThat(calls).hasValue(1);
        }
    }

@Test
void bridgeWorkerRejectsConsentWithdrawalBeforeClassification()
        throws Exception {

    var base = ConsentAwareCustomerMemoryTest.synthetic(true);

    var active = new DemoCustomerContextClient.Snapshot(
            base.customerId(),
            base.selectionId(),
            base.personalizationConsent(),
            base.consentEpoch(),
            true,
            UUID.randomUUID()
    );

    var state = new AtomicReference<
            DemoCustomerContextClient.Snapshot>(active);

    var authorizationStarted =
            new java.util.concurrent.CountDownLatch(1);
    var releaseAuthorization =
            new java.util.concurrent.CountDownLatch(1);
    var workerFinished =
            new java.util.concurrent.CountDownLatch(1);

    var classifierCalls = new AtomicInteger();

    try (var shadow = new RetailIntentShadow(
            question -> {
                classifierCalls.incrementAndGet();

                return new RetailIntentClassification(
                        RetailIntentClassification.Intent.PRODUCT,
                        "synthetic-model",
                        true);
            },
            workerFinished::countDown);

         var server = new RetailChatHttpServer(
                 0,
                 TOKEN,
                 Duration.ofSeconds(2),
                 cookie -> Optional.ofNullable(state.get()),
                 (cookie, question) -> answer(false),
                 request -> shadow.observe(
        request.message(),
        () -> {
                             authorizationStarted.countDown();

                             try {
                                 if (!releaseAuthorization.await(
                                         3,
                                         java.util.concurrent.TimeUnit.SECONDS)) {
                                     return false;
                                 }
                             } catch (InterruptedException interrupted) {
                                 Thread.currentThread().interrupt();
                                 return false;
                             }

                             return request.authorized().getAsBoolean();
                         }
                 ))) {

        server.start();

        try {
           var response = send(
        server,
        TOKEN,
        Map.of(),
        "Show me product ELEC-1001"
);

            // Java routing remains available.
            assertThat(response.statusCode()).isEqualTo(200);

            // Worker has started but has not authorized Gemini.
            assertThat(authorizationStarted.await(
                    2,
                    java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            // Withdraw AI-processing consent while the worker waits.
            state.set(new DemoCustomerContextClient.Snapshot(
                    active.customerId(),
                    active.selectionId(),
                    active.personalizationConsent(),
                    active.consentEpoch(),
                    false,
                    UUID.randomUUID()
            ));

        } finally {
            releaseAuthorization.countDown();
        }

        assertThat(workerFinished.await(
                3,
                java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // No model invocation after consent withdrawal.
        assertThat(classifierCalls).hasValue(0);
    }
}

@Test
void bridgeWorkerReceivesExactMessageWithValidConsent()
        throws Exception {

    var base = ConsentAwareCustomerMemoryTest.synthetic(true);

    var active = new DemoCustomerContextClient.Snapshot(
            base.customerId(),
            base.selectionId(),
            base.personalizationConsent(),
            base.consentEpoch(),
            true,
            UUID.randomUUID()
    );

    var classifierCalls = new AtomicInteger();
    var receivedQuestion = new AtomicReference<String>();
    var workerFinished =
            new java.util.concurrent.CountDownLatch(1);

    var question = "Show me product ELEC-1001";

    try (var shadow = new RetailIntentShadow(
            message -> {
                classifierCalls.incrementAndGet();
                receivedQuestion.set(message);

                return new RetailIntentClassification(
                        RetailIntentClassification.Intent.PRODUCT,
                        "synthetic-model",
                        true);
            },
            workerFinished::countDown);

         var server = new RetailChatHttpServer(
                 0,
                 TOKEN,
                 Duration.ofSeconds(2),
                 cookie -> Optional.of(active),
                 (cookie, message) -> answer(false),
                 request -> shadow.observe(
                         request.message(),
                         request.authorized()
                 ))) {

        server.start();

        var response = send(
                server,
                TOKEN,
                Map.of(),
                question
        );

        assertThat(response.statusCode()).isEqualTo(200);

        assertThat(workerFinished.await(
                3,
                java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        assertThat(classifierCalls).hasValue(1);
        assertThat(receivedQuestion.get()).isEqualTo(question);
    }
}
    @Test
    void checksSessionBeforeAndAfterAndFencesConsentChanges() throws Exception {
        var active = ConsentAwareCustomerMemoryTest.synthetic(true);
        var state = new AtomicReference<DemoCustomerContextClient.Snapshot>();
        try (var server = new RetailChatHttpServer(0, TOKEN, Duration.ofSeconds(2), cookie -> Optional.ofNullable(state.get()),
                (cookie, question) -> {
                   state.set(new DemoCustomerContextClient.Snapshot(
        active.customerId(),
        active.selectionId(),
        false,
        UUID.randomUUID(),
        false,
        UUID.randomUUID()
));
                    return answer(true);
                })) {
            server.start();
            assertThat(send(server, TOKEN, Map.of()).statusCode()).isEqualTo(403);
            state.set(active);
            var reply = send(server, TOKEN, Map.of());
            assertThat(reply.statusCode()).isEqualTo(409);
            assertThat(reply.body()).contains("SESSION_CHANGED").doesNotContain("catalog details");
        }
    }
	
	
@Test
void shadowObserverDoesNotRunWhenAgentFails() throws Exception {

    var active = ConsentAwareCustomerMemoryTest.synthetic(false);
    var observations = new AtomicInteger();

    try (var server = new RetailChatHttpServer(
            0,
            TOKEN,
            Duration.ofSeconds(2),
            cookie -> Optional.of(active),
            (cookie, question) -> {
                throw new IllegalStateException(
                        "Synthetic agent execution failure");
            },
            message -> observations.incrementAndGet())) {

        server.start();

        var response = send(server, TOKEN, Map.of());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("ADK_UNAVAILABLE");
        assertThat(observations).hasValue(0);
    }
}


    @Test
    void boundsExecutionTime() throws Exception {
        var active = ConsentAwareCustomerMemoryTest.synthetic(false);
        try (var server = new RetailChatHttpServer(0, TOKEN, Duration.ofMillis(120), cookie -> Optional.of(active),
                (cookie, question) -> {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                    }
                    return answer(false);
                })) {
            server.start();
            var result = send(server, TOKEN, Map.of());
            assertThat(result.statusCode()).isEqualTo(504);
            assertThat(result.body()).contains("ADK_TIMEOUT");
        }
    }

    private static PersonalizedRetailAgent.Result answer(boolean personalized) {
        return new PersonalizedRetailAgent.Result("catalog details", Optional.empty(), Optional.empty(),
                personalized, List.of());
    }
	
	@Test
void shadowObserverDoesNotRunWithoutAiProcessingConsent()
        throws Exception {

    // Personalization enabled, AI processing disabled.
    var active = ConsentAwareCustomerMemoryTest.synthetic(true);
    var observations = new AtomicInteger();

    try (var server = new RetailChatHttpServer(
            0,
            TOKEN,
            Duration.ofSeconds(2),
            cookie -> Optional.of(active),
            (cookie, question) -> answer(true),
            message -> observations.incrementAndGet())) {

        server.start();

        var response = send(server, TOKEN, Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(observations).hasValue(0);
    }
}
@Test
void shadowObserverDoesNotRunWhenAiConsentChangesDuringExecution()
        throws Exception {

    var base = ConsentAwareCustomerMemoryTest.synthetic(true);

    var active = new DemoCustomerContextClient.Snapshot(
            base.customerId(),
            base.selectionId(),
            base.personalizationConsent(),
            base.consentEpoch(),
            true,
            UUID.randomUUID()
    );

    var state = new AtomicReference<
            DemoCustomerContextClient.Snapshot>(active);

    var observations = new AtomicInteger();

    try (var server = new RetailChatHttpServer(
            0,
            TOKEN,
            Duration.ofSeconds(2),
            cookie -> Optional.ofNullable(state.get()),
            (cookie, question) -> {

                // Simulate AI consent withdrawal during execution.
                state.set(new DemoCustomerContextClient.Snapshot(
                        active.customerId(),
                        active.selectionId(),
                        active.personalizationConsent(),
                        active.consentEpoch(),
                        false,
                        UUID.randomUUID()
                ));

                return answer(false);
            },
            message -> observations.incrementAndGet())) {

        server.start();

        var response = send(server, TOKEN, Map.of());

        // Existing Java routing remains available.
        assertThat(response.statusCode()).isEqualTo(200);

        // Gemini shadow processing must be blocked.
        assertThat(observations).hasValue(0);
    }
}

@Test
void shadowRequestRejectsConsentWithdrawalAfterScheduling()
        throws Exception {

    var base = ConsentAwareCustomerMemoryTest.synthetic(true);
    var active = new DemoCustomerContextClient.Snapshot(
            base.customerId(),
            base.selectionId(),
            base.personalizationConsent(),
            base.consentEpoch(),
            true,
            UUID.randomUUID()
    );

    var state = new AtomicReference<
            DemoCustomerContextClient.Snapshot>(active);

    var captured = new AtomicReference<
            RetailChatHttpServer.ShadowRequest>();

    try (var server = new RetailChatHttpServer(
            0,
            TOKEN,
            Duration.ofSeconds(2),
            cookie -> Optional.ofNullable(state.get()),
            (cookie, question) -> answer(false),
            captured::set)) {

        server.start();

        var response = send(server, TOKEN, Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();

        // Consent is valid when the shadow request is scheduled.
        assertThat(captured.get().authorized().getAsBoolean())
                .isTrue();

        // Customer withdraws AI-processing consent.
        state.set(new DemoCustomerContextClient.Snapshot(
                active.customerId(),
                active.selectionId(),
                active.personalizationConsent(),
                active.consentEpoch(),
                false,
                UUID.randomUUID()
        ));

        // Worker-time authorization must reject the request.
        assertThat(captured.get().authorized().getAsBoolean())
                .isFalse();
    }
}

@Test
void shadowRequestRejectsConsentEpochRotation()
        throws Exception {

    var base = ConsentAwareCustomerMemoryTest.synthetic(true);

    var active = new DemoCustomerContextClient.Snapshot(
            base.customerId(),
            base.selectionId(),
            base.personalizationConsent(),
            base.consentEpoch(),
            true,
            UUID.randomUUID()
    );

    var state = new AtomicReference<
            DemoCustomerContextClient.Snapshot>(active);

    var captured = new AtomicReference<
            RetailChatHttpServer.ShadowRequest>();

    try (var server = new RetailChatHttpServer(
            0,
            TOKEN,
            Duration.ofSeconds(2),
            cookie -> Optional.ofNullable(state.get()),
            (cookie, question) -> answer(false),
            captured::set)) {

        server.start();

        var response = send(server, TOKEN, Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();

        // Original consent is valid.
        assertThat(captured.get().authorized().getAsBoolean())
                .isTrue();

        // Simulate withdrawal followed by renewed consent.
        // Consent is enabled again, but the epoch has changed.
        state.set(new DemoCustomerContextClient.Snapshot(
                active.customerId(),
                active.selectionId(),
                active.personalizationConsent(),
                active.consentEpoch(),
                true,
                UUID.randomUUID()
        ));

        // Old queued request must remain unauthorized.
        assertThat(captured.get().authorized().getAsBoolean())
                .isFalse();
    }
}

@Test
void shadowRequestRejectsSessionReplacement()
        throws Exception {

    var base = ConsentAwareCustomerMemoryTest.synthetic(true);

    var active = new DemoCustomerContextClient.Snapshot(
            base.customerId(),
            base.selectionId(),
            base.personalizationConsent(),
            base.consentEpoch(),
            true,
            UUID.randomUUID()
    );

    var state = new AtomicReference<
            DemoCustomerContextClient.Snapshot>(active);

    var captured = new AtomicReference<
            RetailChatHttpServer.ShadowRequest>();

    try (var server = new RetailChatHttpServer(
            0,
            TOKEN,
            Duration.ofSeconds(2),
            cookie -> Optional.ofNullable(state.get()),
            (cookie, question) -> answer(false),
            captured::set)) {

        server.start();

        var response = send(server, TOKEN, Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(captured.get()).isNotNull();

        // Original customer session is authorized.
        assertThat(captured.get().authorized().getAsBoolean())
                .isTrue();

        // Simulate customer selection/session replacement.
        state.set(new DemoCustomerContextClient.Snapshot(
                active.customerId(),
                UUID.randomUUID(),
                active.personalizationConsent(),
                active.consentEpoch(),
                true,
                active.aiProcessingEpoch()
        ));

        // Previously scheduled request must be rejected.
        assertThat(captured.get().authorized().getAsBoolean())
                .isFalse();
    }
}
	
@Test
void shadowObserverRunsOnlyAfterSuccessfulSessionValidation()
        throws Exception {

    var base = ConsentAwareCustomerMemoryTest.synthetic(true);

var active = new DemoCustomerContextClient.Snapshot(
        base.customerId(),
        base.selectionId(),
        base.personalizationConsent(),
        base.consentEpoch(),
        true,
        UUID.randomUUID()
);

    var state = new AtomicReference<
            DemoCustomerContextClient.Snapshot>(active);

    var observations = new AtomicInteger();

    try (var server = new RetailChatHttpServer(
            0,
            TOKEN,
            Duration.ofSeconds(2),
            cookie -> Optional.ofNullable(state.get()),
            (cookie, question) -> answer(true),
            message -> observations.incrementAndGet())) {

        server.start();

        // 1. Valid session: observer should execute.
        var success = send(server, TOKEN, Map.of());

        assertThat(success.statusCode()).isEqualTo(200);
        assertThat(observations).hasValue(1);

        // 2. Missing session: observer must not execute.
        state.set(null);

        var missing = send(server, TOKEN, Map.of());

        assertThat(missing.statusCode()).isEqualTo(403);
        assertThat(observations).hasValue(1);

        // 3. Consent changes during agent execution.
        state.set(active);
    }

    var changedObservations = new AtomicInteger();

    var changingState = new AtomicReference<
            DemoCustomerContextClient.Snapshot>(active);

    try (var server = new RetailChatHttpServer(
            0,
            TOKEN,
            Duration.ofSeconds(2),
            cookie -> Optional.ofNullable(changingState.get()),
            (cookie, question) -> {

                changingState.set(
                        new DemoCustomerContextClient.Snapshot(
                                active.customerId(),
                                active.selectionId(),
                                false,
                                UUID.randomUUID(),false,
        UUID.randomUUID()
                        )
                );

                return answer(true);
            },
           request -> {
    assertThat(request.message()).isNotBlank();
    assertThat(request.authorized().getAsBoolean()).isTrue();
    observations.incrementAndGet();
})) {

        server.start();

        var changed = send(server, TOKEN, Map.of());

        assertThat(changed.statusCode()).isEqualTo(409);
        assertThat(changed.body()).contains("SESSION_CHANGED");
        assertThat(changedObservations).hasValue(0);
    }
}


  private HttpResponse<String> send(
        RetailChatHttpServer server,
        String token,
        Map<String, String> headers) throws Exception {

    return send(server, token, headers, "Tell me about KB-201.");
}

private HttpResponse<String> send(
        RetailChatHttpServer server,
        String token,
        Map<String, String> headers,
        String message) throws Exception {

    var target = HttpRequest.newBuilder(
            URI.create(
                    "http://127.0.0.1:" + server.port()
                    + "/internal/v1/retail/ask"))
            .timeout(Duration.ofSeconds(4))
            .header("Content-Type", "application/json");

    if (token != null) {
        target.header("Authorization", "Bearer " + token);
    }

    headers.forEach(target::header);

    return http.send(
            target.POST(
                    HttpRequest.BodyPublishers.ofString(
                            JSON.writeValueAsString(
                                    Map.of(
                                            "message", message,
                                            "sessionCookie", COOKIE
                                    )
                            )
                    )
            ).build(),
            HttpResponse.BodyHandlers.ofString()
    );
}
}

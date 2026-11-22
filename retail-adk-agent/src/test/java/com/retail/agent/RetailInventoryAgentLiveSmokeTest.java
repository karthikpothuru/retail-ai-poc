package com.retail.agent;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional live integration smoke test that executes a real end-to-end conversation turn
 * with Gemini 3.8 Flash through Google ADK, verifying live function-calling tool dispatch.
 * <p>
 * Kept disabled by default so regular CI/CD builds do not require network access or API credentials.
 * Run explicitly with a valid GEMINI_API_KEY:
 * {@code mvn test -Dtest=RetailInventoryAgentLiveSmokeTest -DrunLiveSmokeTest=true}
 */
@DisplayName("RetailInventoryAgent Live Gemini Smoke Test")
class RetailInventoryAgentLiveSmokeTest {

    @Test
    @DisplayName("Should invoke live Gemini model, execute ADK tool call, and return grounded answer")
    @EnabledIfSystemProperty(named = "runLiveSmokeTest", matches = "true")
    void shouldExecuteLiveGeminiToolCall() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        assertThat(apiKey != null && !apiKey.isBlank())
                .withFailMessage("GEMINI_API_KEY environment variable must be set to execute live integration test.")
                .isTrue();

        String prompt = "What is the current available inventory for SKU-ELEC-1001 at warehouse WH-CENTRAL-01?";

        RunConfig runConfig = RunConfig.builder().build();
        InMemoryRunner runner = new InMemoryRunner(RetailInventoryAgent.ROOT_AGENT);

        Session session = runner.sessionService()
                .createSession(runner.appName(), "live-smoke-test-user")
                .blockingGet();

        Content userMessage = Content.fromParts(Part.fromText(prompt));
        Flowable<Event> events = runner.runAsync(
                session.userId(),
                session.id(),
                userMessage,
                runConfig
        );

        List<String> invokedToolNames = new ArrayList<>();
        List<Map<String, Object>> capturedToolArgs = new ArrayList<>();
        List<Map<String, Object>> capturedToolResults = new ArrayList<>();
        AtomicReference<String> finalAgentResponse = new AtomicReference<>("");

        events.blockingForEach(event -> {
            for (FunctionCall call : event.functionCalls()) {
                call.name().ifPresent(invokedToolNames::add);
                call.args().ifPresent(capturedToolArgs::add);
            }
            for (FunctionResponse resp : event.functionResponses()) {
                resp.response().ifPresent(capturedToolResults::add);
            }
            if (event.finalResponse()) {
                finalAgentResponse.set(event.stringifyContent());
            }
        });

        // Output captured test evidence (without any credentials)
        System.out.println("==================================================");
        System.out.println("LIVE SMOKE TEST: GEMINI ADK FUNCTION CALL CAPTURE");
        System.out.println("==================================================");
        System.out.println("User Prompt: " + prompt);
        System.out.println("Selected Tool Name(s): " + invokedToolNames);
        System.out.println("Tool Arguments: " + capturedToolArgs);
        System.out.println("Tool Execution Result: " + capturedToolResults);
        System.out.println("Final Agent Response:\n" + finalAgentResponse.get());
        System.out.println("==================================================");

        // Assertions verifying live function calling
        assertThat(invokedToolNames)
                .as("Gemini should have selected lookup_inventory tool")
                .contains("lookup_inventory");

        assertThat(capturedToolArgs)
                .as("Tool arguments should include SKU-ELEC-1001 and WH-CENTRAL-01")
                .anyMatch(args -> "SKU-ELEC-1001".equalsIgnoreCase(String.valueOf(args.get("sku")))
                        && "WH-CENTRAL-01".equalsIgnoreCase(String.valueOf(args.get("warehouseId"))));

        assertThat(capturedToolResults)
                .as("Tool execution result should report availableQuantity = 115")
                .anyMatch(res -> res.containsKey("availableQuantity")
                        && "115".equals(String.valueOf(res.get("availableQuantity"))));

        assertThat(finalAgentResponse.get())
                .as("Final agent response should contain grounded inventory metrics")
                .contains("115");
    }
}

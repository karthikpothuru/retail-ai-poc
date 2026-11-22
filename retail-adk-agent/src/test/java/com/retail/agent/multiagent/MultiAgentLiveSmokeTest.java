package com.retail.agent.multiagent;

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
import java.util.concurrent.TimeUnit;
import com.retail.agent.observability.AgentAuditLogger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional live integration test that verifies multi-agent delegation from the
 * {@link RetailOperationsCoordinatorAgent} to a specialist sub-agent with Gemini 3.8 Flash.
 * <p>
 * Disabled by default so standard offline unit tests do not require Google Cloud credentials.
 * Run explicitly with valid credentials:
 * {@code mvn test -Dtest=MultiAgentLiveSmokeTest -DrunLiveMultiAgentTest=true}
 */
@DisplayName("Phase 5A: Multi-Agent Live Gemini Delegation Smoke Test")
class MultiAgentLiveSmokeTest {

    @Test
    @DisplayName("Should invoke live Gemini, delegate from coordinator to specialist, and return grounded answer")
    @EnabledIfSystemProperty(named = "runLiveMultiAgentTest", matches = "true")
    void shouldExecuteLiveMultiAgentDelegation() {
        String apiKey = System.getenv("GEMINI_API_KEY");
        assertThat(apiKey != null && !apiKey.isBlank())
                .withFailMessage("GEMINI_API_KEY environment variable must be set to execute live integration test.")
                .isTrue();

        String prompt = "Please check the current inventory balance for SKU-ELEC-1001 in warehouse WH-CENTRAL-01.";

        RunConfig runConfig = RunConfig.builder().maxLlmCalls(20).build();
        InMemoryRunner runner = new InMemoryRunner(RetailOperationsCoordinatorAgent.ROOT_AGENT);

        Session session = runner.sessionService()
                .createSession(runner.appName(), "live-multiagent-tester")
                .blockingGet();

        Content userMessage = Content.fromParts(Part.fromText(prompt));
        Flowable<Event> events = runner.runAsync(
                session.userId(),
                session.id(),
                userMessage,
                runConfig
        );

        List<String> invokedTools = new ArrayList<>();
        List<Map<String, Object>> invokedArgs = new ArrayList<>();
        List<Object> executionResponses = new ArrayList<>();
        AtomicReference<String> finalAgentResponse = new AtomicReference<>("");
        List<Event> recordedEvents = new ArrayList<>();

        events.timeout(120, TimeUnit.SECONDS).blockingForEach(event -> {
            recordedEvents.add(event);
            for (FunctionCall call : event.functionCalls()) {
                invokedTools.add(call.name().orElse("unknown_tool"));
                invokedArgs.add(call.args().orElse(Map.of()));
            }

            for (FunctionResponse resp : event.functionResponses()) {
                resp.response().ifPresent(executionResponses::add);
            }

            if (event.finalResponse()) {
                finalAgentResponse.set(event.stringifyContent());
            }
        });

        // Verification of live multi-agent interaction
        assertThat(invokedTools).isNotEmpty();
        assertThat(invokedTools).contains("transfer_to_agent", "lookup_inventory");
        assertThat(invokedArgs).anySatisfy(args ->
                assertThat(args).containsEntry("agent_name", "inventory_specialist"));
        assertThat(finalAgentResponse.get()).isNotBlank();
        verifyExecution(recordedEvents, "inventory_specialist", "lookup_inventory");

        System.out.println("=================================================================");
        System.out.println("  LIVE MULTI-AGENT SMOKE TEST RESULTS");
        System.out.println("=================================================================");
        System.out.println("Prompt           : " + prompt);
        System.out.println("Tools Invoked    : " + invokedTools);
        System.out.println("Arguments        : " + invokedArgs);
        System.out.println("Responses Count  : " + executionResponses.size());
        System.out.println("Final Synthesis  : " + finalAgentResponse.get());
        System.out.println("=================================================================");

        var discrepancy = runTurn(runner, session, runConfig,
                "Investigate cycle count discrepancies for that same SKU and warehouse.");
        verifyExecution(discrepancy, "discrepancy_specialist", "investigate_discrepancy");
        var replenishment = runTurn(runner, session, runConfig,
                "Does that same item in that same warehouse need replenishment?");
        verifyExecution(replenishment, "replenishment_specialist", "calculate_replenishment");
        var compound = runTurn(runner, session, runConfig,
                "For SKU-ELEC-1001 in WH-CENTRAL-01 check current stock, investigate recorded discrepancies, "
                        + "and calculate replenishment. Give a complete combined answer covering all three.");
        verifyExecution(compound, "inventory_specialist", "lookup_inventory");
        verifyExecution(compound, "discrepancy_specialist", "investigate_discrepancy");
        verifyExecution(compound, "replenishment_specialist", "calculate_replenishment");
    }

    private static List<Event> runTurn(InMemoryRunner runner, Session session, RunConfig config, String prompt) {
        System.out.println("LIVE TURN Session=" + session.id() + " Prompt=" + prompt);
        var events = runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText(prompt)), config)
                .timeout(120, TimeUnit.SECONDS).toList().blockingGet();
        return events;
    }

    private static void verifyExecution(List<Event> events, String specialist, String tool) {
        assertThat(events).anySatisfy(event -> {
            assertThat(event.author()).isEqualTo(RetailOperationsCoordinatorAgent.AGENT_NAME);
            assertThat(event.actions().transferToAgent()).contains(specialist);
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.author()).isEqualTo(specialist);
            assertThat(event.actions().transferToAgent()).contains(RetailOperationsCoordinatorAgent.AGENT_NAME);
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.author()).isEqualTo(specialist);
            assertThat(event.functionCalls()).anySatisfy(call -> {
                assertThat(call.name()).contains(tool);
                assertThat(call.args().orElseThrow()).containsEntry("sku", "SKU-ELEC-1001")
                        .containsEntry("warehouseId", "WH-CENTRAL-01");
            });
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.author()).isEqualTo(specialist);
            assertThat(event.functionResponses()).anySatisfy(response -> {
                assertThat(response.name()).contains(tool);
                assertThat(response.response().orElseThrow()).isNotEmpty();
                System.out.println("LIVE TOOL Author=" + specialist + " Tool=" + tool + " Result="
                        + AgentAuditLogger.sanitizeMap(response.response().orElseThrow()));
            });
        });
        var finals = events.stream().filter(Event::finalResponse)
                .filter(event -> !event.stringifyContent().isBlank()).toList();
        assertThat(finals).isNotEmpty();
        assertThat(finals.get(finals.size() - 1).author()).isEqualTo(RetailOperationsCoordinatorAgent.AGENT_NAME);
        events.forEach(event -> event.actions().transferToAgent().ifPresent(target ->
                System.out.println("LIVE TRANSFER Author=" + event.author() + " Target=" + target)));
        System.out.println("LIVE FINAL " + finals.get(finals.size() - 1).stringifyContent());
    }
}

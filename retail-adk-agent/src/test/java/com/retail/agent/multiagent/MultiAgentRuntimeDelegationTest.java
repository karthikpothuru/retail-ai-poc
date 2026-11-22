package com.retail.agent.multiagent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.retail.agent.observability.AgentAuditLogger;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class MultiAgentRuntimeDelegationTest {
    @Test
    void compoundRequestReturnsThroughCoordinatorAndPreservesContextOnNextTurn() {
        AgentAuditLogger.clearAuditHistory();
        var store = InMemoryRetailInventoryStore.createWithDefaultData();
        store.updateStock("SKU-ELEC-1001", "WH-CENTRAL-01", 999, 50);
        var production = (LlmAgent) RetailOperationsCoordinatorAgent.createCoordinator(store);
        var back = call("transfer_to_agent", Map.of("agent_name", production.name()));
        var args = Map.<String, Object>of("sku", "SKU-ELEC-1001", "warehouseId", "WH-CENTRAL-01");
        String complete = "Inventory: 999 on hand, 949 available. Discrepancy: recorded audits found. "
                + "Replenishment: 949 available, no reorder needed; advisory only.";
        var coordinator = new ScriptedModel(
                call("transfer_to_agent", Map.of("agent_name", "inventory_specialist")),
                call("transfer_to_agent", Map.of("agent_name", "discrepancy_specialist")),
                call("transfer_to_agent", Map.of("agent_name", "replenishment_specialist")),
                Part.fromText(complete),
                call("transfer_to_agent", Map.of("agent_name", "replenishment_specialist")),
                Part.fromText("The same SKU and warehouse still have 949 available; no reorder needed."));
        var inventory = new ScriptedModel(call("lookup_inventory", args), back);
        var discrepancy = new ScriptedModel(call("investigate_discrepancy", args), back);
        var replenishment = new ScriptedModel(call("calculate_replenishment", args), back,
                call("calculate_replenishment", args), back);
        Map<String, ScriptedModel> models = Map.of("inventory_specialist", inventory,
                "discrepancy_specialist", discrepancy, "replenishment_specialist", replenishment);
        var children = production.subAgents().stream()
                .map(agent -> (BaseAgent) copy((LlmAgent) agent, models.get(agent.name()), List.of())).toList();
        var root = copy(production, coordinator, children);
        children.forEach(child -> {
            var specialist = (LlmAgent) child;
            assertThat(specialist.disallowTransferToParent()).isFalse();
            assertThat(specialist.disallowTransferToPeers()).isTrue();
            assertThat(specialist.instruction().toString()).contains("RETURN TO COORDINATOR");
        });
        var runner = new InMemoryRunner(root);
        var session = runner.sessionService().createSession(runner.appName(), "compound-offline").blockingGet();
        var events = runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText(
                "For SKU-ELEC-1001 in WH-CENTRAL-01 check stock, discrepancies, and replenishment.")),
                RunConfig.builder().maxLlmCalls(16).build()).toList().blockingGet();
        assertThat(events.stream().flatMap(event -> event.actions().transferToAgent().stream()).toList())
                .containsExactly("inventory_specialist", production.name(), "discrepancy_specialist",
                        production.name(), "replenishment_specialist", production.name());
        assertThat(events.stream().filter(Event::finalResponse).filter(event ->
                !event.stringifyContent().isBlank()).toList()).singleElement().satisfies(event -> {
                    assertThat(event.author()).isEqualTo(production.name());
                    assertThat(event.stringifyContent()).contains(complete);
                });
        // Completeness here means every actual tool result reaches the synthesis request.
        // The scripted final text is a transport assertion, not an evaluation of Gemini quality.
        var synthesis = coordinator.requests.get(3);
        String specialistTranscripts = synthesis.contents().stream()
                .flatMap(content -> content.parts().orElse(List.of()).stream())
                .flatMap(part -> part.text().stream()).collect(java.util.stream.Collectors.joining("\n"));
        assertThat(specialistTranscripts).contains("lookup_inventory", "investigate_discrepancy",
                "calculate_replenishment", "onHandQuantity", "999", "availableQuantity", "949",
                "totalAuditCount", "reorderNeeded", "false");
        assertThat(AgentAuditLogger.getAuditHistory().stream()
                .filter(entry -> entry.outcome().equals("SUCCESS"))
                .map(AgentAuditLogger.AuditEntry::toolName).toList())
                .contains("lookup_inventory", "investigate_discrepancy", "calculate_replenishment");
        runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText(
                "Check replenishment again for that same item and warehouse.")),
                RunConfig.builder().maxLlmCalls(8).build()).toList().blockingGet();
        assertThat(coordinator.requests).hasSize(6);
        assertThat(replenishment.requests).hasSize(4);
        var followUpHistory = replenishment.requests.get(2).contents().toString();
        assertThat(followUpHistory).contains("SKU-ELEC-1001", "WH-CENTRAL-01", "that same item");
        assertThat(replenishment.requests.get(0).getSystemInstructions().toString())
                .contains("retail_operations_coordinator");
    }

    @Test
    void executesRealTransfersToolsCallbacksAndSessionContinuationWithoutNetwork() {
        Map<String, String> cases = Map.of(
                "inventory_specialist", "lookup_inventory",
                "discrepancy_specialist", "investigate_discrepancy",
                "replenishment_specialist", "calculate_replenishment");
        cases.forEach((target, tool) -> {
            AgentAuditLogger.clearAuditHistory();
            var store = InMemoryRetailInventoryStore.createWithDefaultData();
            var production = (LlmAgent) RetailOperationsCoordinatorAgent.createCoordinator(store);
            var coordinatorModel = new ScriptedModel(call("transfer_to_agent", Map.of("agent_name", target)));
            var specialistModel = new ScriptedModel(
                    call(tool, Map.of("sku", "SKU-ELEC-1001", "warehouseId", "WH-CENTRAL-01")),
                    Part.fromText("Tool execution completed."), Part.fromText("Follow-up completed."));

            // Rebuild the production topology with scripted model transport only. Keep actual
            // production FunctionTool instances, instructions, transfer flags, and callbacks.
            List<BaseAgent> specialists = production.subAgents().stream()
                    .map(agent -> (BaseAgent) copy((LlmAgent) agent,
                            agent.name().equals(target) ? specialistModel : new ScriptedModel(), List.of()))
                    .toList();
            var root = copy(production, coordinatorModel, specialists);
            var runner = new InMemoryRunner(root);
            var session = runner.sessionService().createSession(runner.appName(), "offline-runtime").blockingGet();
            store.updateStock("SKU-ELEC-1001", "WH-CENTRAL-01", 999, 50);
            List<Event> events = runner.runAsync(session.userId(), session.id(),
                    Content.fromParts(Part.fromText("Run " + tool)), RunConfig.builder().build())
                    .toList().blockingGet();

            assertThat(coordinatorModel.requests.get(0).tools()).containsKey("transfer_to_agent");
            assertThat(events).anySatisfy(event ->
                    assertThat(event.actions().transferToAgent()).contains(target));
            assertThat(events).anySatisfy(event -> {
                assertThat(event.author()).isEqualTo(target);
                assertThat(event.functionResponses()).anySatisfy(response -> {
                    assertThat(response.name()).contains(tool);
                    if (!tool.equals("investigate_discrepancy")) {
                        assertThat(response.response().orElseThrow()).containsEntry("availableQuantity", 949);
                    }
                });
            });
            var history = AgentAuditLogger.getAuditHistory();
            assertThat(history).anySatisfy(entry -> {
                assertThat(entry.outcome()).isEqualTo("AGENT_DELEGATION");
                assertThat(entry.targetAgent()).isEqualTo(target);
                assertThat(entry.sessionId()).isEqualTo(session.id());
            });
            assertThat(history).anySatisfy(entry -> {
                assertThat(entry.outcome()).isEqualTo("SUCCESS");
                assertThat(entry.agentName()).isEqualTo(target);
                assertThat(entry.toolName()).isEqualTo(tool);
                assertThat(entry.durationMs()).isGreaterThanOrEqualTo(0);
            });
            assertThat(history).anySatisfy(entry -> {
                assertThat(entry.outcome()).isEqualTo("AGENT_START");
                assertThat(entry.agentName()).isEqualTo(target);
            });
            assertThat(history).anySatisfy(entry -> {
                assertThat(entry.outcome()).isEqualTo("AGENT_END");
                assertThat(entry.agentName()).isEqualTo(target);
            });
            // A specialist final answer leaves it active for the next user turn.
            var followUp = runner.runAsync(session.userId(), session.id(),
                    Content.fromParts(Part.fromText("Continue")), RunConfig.builder().build())
                    .toList().blockingGet();
            assertThat(followUp).anySatisfy(event -> {
                assertThat(event.author()).isEqualTo(target);
                assertThat(event.finalResponse()).isTrue();
            });
            assertThat(specialistModel.requests).hasSize(3);
            assertThat(specialistModel.requests.get(2).contents().size())
                    .isGreaterThan(specialistModel.requests.get(0).contents().size());
        });
    }

    private static Part call(String name, Map<String, Object> args) {
        return Part.fromFunctionCall(name, args);
    }

    private static LlmAgent copy(LlmAgent source, BaseLlm model, List<BaseAgent> children) {
        return LlmAgent.builder().name(source.name()).description(source.description())
                .model(model).instruction(source.instruction()).tools(source.tools().blockingGet())
                .subAgents(children)
                .disallowTransferToParent(source.disallowTransferToParent())
                .disallowTransferToPeers(source.disallowTransferToPeers())
                .beforeAgentCallback(new ArrayList<>(source.beforeAgentCallback()))
                .afterAgentCallback(new ArrayList<>(source.afterAgentCallback()))
                .beforeToolCallback(source.beforeToolCallback())
                .afterToolCallback(source.afterToolCallback())
                .onToolErrorCallback(source.onToolErrorCallback()).build();
    }

    private static final class ScriptedModel extends BaseLlm {
        private final Queue<Part> responses;
        private final List<LlmRequest> requests = new ArrayList<>();

        ScriptedModel(Part... responses) {
            super("offline-scripted-model");
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
            requests.add(request);
            if (responses.isEmpty()) {
                return Flowable.error(new AssertionError("Unexpected model invocation"));
            }
            return Flowable.just(LlmResponse.builder().content(Content.builder().role("model")
                    .parts(List.of(responses.remove())).build()).build());
        }

        @Override
        public BaseLlmConnection connect(LlmRequest request) {
            throw new UnsupportedOperationException("Offline test");
        }
    }
}

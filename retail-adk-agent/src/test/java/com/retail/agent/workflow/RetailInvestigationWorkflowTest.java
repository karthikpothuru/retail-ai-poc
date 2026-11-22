package com.retail.agent.workflow;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.BaseLlmConnection;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.retail.agent.RetailInventoryAgent;
import com.retail.agent.multiagent.RetailOperationsCoordinatorAgent;
import com.retail.agent.observability.AgentAuditLogger;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import com.retail.agent.store.RetailInventoryStore;
import com.retail.agent.tools.DiscrepancyInvestigationTool;
import com.retail.agent.tools.ReplenishmentCalculatorTool;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class RetailInvestigationWorkflowTest {
    private static final String SKU = "SKU-ELEC-1001";
    private static final String WAREHOUSE = "WH-CENTRAL-01";
    private static final List<String> TOOLS = List.of("lookup_inventory", "investigate_discrepancy", "calculate_replenishment");
    private RetailInventoryStore store;
    private Map<String, ScriptedModel> models;
    private InMemoryRunner runner;
    private Session session;

    @BeforeEach
    void setUp() {
        store = InMemoryRetailInventoryStore.createWithDefaultData();
        models = Map.of("inventory", scripted("lookup_inventory"), "discrepancy", scripted("investigate_discrepancy"),
                "replenishment", scripted("calculate_replenishment"));
        configure();
        AgentAuditLogger.clearAuditHistory();
    }

    private void configure() {
        runner = new InMemoryRunner(RetailInvestigationWorkflow.create(store, models::get));
        session = runner.sessionService().createSession(runner.appName(), "offline-sequential").blockingGet();
    }

    @Test
    void configuresSeparateOrderedAgentsWithUnchangedProductionModelAndOriginalRoots() {
        var workflow = RetailInvestigationWorkflow.create(store);
        assertThat(workflow.subAgents()).extracting(agent -> agent.name()).containsExactly(
                "investigation_inventory", "investigation_discrepancy", "investigation_replenishment", "investigation_report");
        workflow.subAgents().stream().filter(LlmAgent.class::isInstance).map(LlmAgent.class::cast).forEach(agent -> {
            assertThat(agent.model().orElseThrow().modelName()).contains(RetailInventoryAgent.MODEL_NAME);
            assertThat(agent.disallowTransferToParent()).isTrue();
            assertThat(agent.disallowTransferToPeers()).isTrue();
            assertThat(agent.tools().blockingGet()).hasSize(1);
        });
        assertThat(RetailInventoryAgent.ROOT_AGENT.parentAgent()).isNull();
        assertThat(RetailOperationsCoordinatorAgent.ROOT_AGENT.parentAgent()).isNull();
        assertThat(RetailOperationsCoordinatorAgent.ROOT_AGENT.subAgents()).hasSize(3);
        assertThatThrownBy(() -> RetailInvestigationWorkflow.create(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void executesToolsInOrderPropagatesValidatedStateAndRendersActualFacts() {
        // Mutation after agent construction proves tools use this store, not another default singleton.
        store.updateStock(SKU, WAREHOUSE, 19, 4);
        var events = run(SKU + " " + WAREHOUSE);
        assertThat(toolCalls(events)).containsExactlyElementsOf(TOOLS);
        assertThat(events).allSatisfy(event -> assertThat(event.actions().transferToAgent()).isEmpty());
        assertThat(events.stream().flatMap(event -> event.functionCalls().stream()).toList()).allSatisfy(call ->
                assertThat(call.args().orElseThrow()).containsExactlyInAnyOrderEntriesOf(args(SKU, WAREHOUSE)));
        var request = state(events, InvestigationState.REQUEST);
        assertThat(request).containsEntry("sku", SKU).containsEntry("warehouseId", WAREHOUSE);
        for (String stage : InvestigationState.STAGES) {
            var envelope = state(events, InvestigationState.PREFIX + stage);
            assertThat(envelope.get("request")).isEqualTo(request);
            assertThat(envelope.get("arguments")).isEqualTo(args(SKU, WAREHOUSE));
            assertThat(envelope.get("result")).isInstanceOf(Map.class);
        }
        assertThat(models.get("discrepancy").requests.get(0).getSystemInstructions().toString())
                .contains("Prior inventory result", "availableQuantity=15");
        assertThat(models.get("replenishment").requests.get(0).getSystemInstructions().toString())
                .contains("Prior inventory result", "Prior discrepancy result", "totalAuditCount=1");
        for (var model : models.values()) {
            assertThat(model.requests).hasSize(1); // acknowledgement is generated locally after actual tool success
            var requestConfig = model.requests.get(0).config().orElseThrow();
            assertThat(requestConfig.toolConfig().orElseThrow().functionCallingConfig().orElseThrow()
                    .mode().orElseThrow().toString()).isEqualTo("ANY");
        }
        String report = report(events);
        assertThat(report).contains("onHandQuantity: 19", "reservedQuantity: 4", "availableQuantity: 15");
        var recommendation = new ReplenishmentCalculatorTool(store).calculateReplenishment(SKU, WAREHOUSE);
        assertThat(report).contains("reorderPoint: " + recommendation.reorderPoint(),
                "recommendedOrderQuantity: " + recommendation.recommendedOrderQuantity(),
                "reorderNeeded: " + recommendation.reorderNeeded(), ReplenishmentCalculatorTool.ORDER_STATUS_DISCLAIMER);
        var audit = new DiscrepancyInvestigationTool(store).investigateDiscrepancy(SKU, WAREHOUSE).auditRecords().get(0);
        assertThat(report).contains("recordId: " + audit.recordId(), "timestamp: " + audit.timestamp(),
                "variance: " + audit.variance(), "documentedNotes: " + audit.documentedNotes());
        assertThat(events.stream().flatMap(event -> event.actions().stateDelta().entrySet().stream())
                .filter(entry -> entry.getKey().equals(InvestigationState.REPORT)).map(Map.Entry::getValue).toList())
                .contains(report);
        var history = AgentAuditLogger.getAuditHistory();
        assertThat(history.stream().filter(entry -> entry.outcome().equals("START"))
                .map(AgentAuditLogger.AuditEntry::toolName).toList()).containsExactlyElementsOf(TOOLS);
        assertThat(history).allSatisfy(entry -> {
            assertThat(entry.sessionId()).isEqualTo(session.id());
            assertThat(entry.correlationId()).isEqualTo(request.get("invocationId"));
        });
    }

    @Test
    void asksForClarificationWithoutModelOrToolCallsForMissingAndAmbiguousInputs() {
        for (String input : List.of("Investigate stock", SKU, WAREHOUSE,
                SKU + " SKU-ELEC-1002 " + WAREHOUSE, SKU + " WH-EAST-02 " + WAREHOUSE)) {
            var events = run(input);
            assertThat(toolCalls(events)).isEmpty();
            assertThat(events).noneSatisfy(event -> assertThat(event.author()).isEqualTo(InvestigationReportAgent.NAME));
            assertThat(events.stream().map(Event::stringifyContent).toList())
                    .anySatisfy(text -> assertThat(text).contains("exactly one SKU and one warehouse"));
        }
        models.values().forEach(model -> assertThat(model.requests).isEmpty());
        assertThat(AgentAuditLogger.getAuditHistory()).noneSatisfy(entry -> assertThat(entry.outcome()).isEqualTo("START"));
        // A complete clarification on a later turn starts a fresh valid investigation.
        assertThat(toolCalls(run(SKU + " " + WAREHOUSE))).containsExactlyElementsOf(TOOLS);
    }

    @Test
    void rejectsWrongSkuBeforeAnyToolExecutes() {
        models = Map.of("inventory", new ScriptedModel(request -> call("lookup_inventory", "SKU-ELEC-1002", WAREHOUSE)),
                "discrepancy", models.get("discrepancy"), "replenishment", models.get("replenishment"));
        configure();
        assertThatThrownBy(() -> run(SKU + " " + WAREHOUSE)).hasStackTraceContaining("arguments must match");
        assertThat(models.get("discrepancy").requests).isEmpty();
        assertThat(AgentAuditLogger.getAuditHistory()).noneSatisfy(entry -> assertThat(entry.outcome()).isEqualTo("START"));
    }

    @Test
    void rejectsWrongWarehouseBeforeAnyToolExecutes() {
        models = Map.of("inventory", new ScriptedModel(request -> call("lookup_inventory", SKU, "WH-EAST-02")),
                "discrepancy", models.get("discrepancy"), "replenishment", models.get("replenishment"));
        configure();
        assertThatThrownBy(() -> run(SKU + " " + WAREHOUSE)).hasStackTraceContaining("arguments must match");
        assertThat(AgentAuditLogger.getAuditHistory()).noneSatisfy(entry -> assertThat(entry.outcome()).isEqualTo("START"));
    }

    @Test
    void rejectsMissingToolAndDoesNotPublishFabricatedStageText() {
        models = Map.of("inventory", new ScriptedModel(request -> Content.fromParts(
                        Part.fromText("There are 999999 units; purchase order placed."))),
                "discrepancy", models.get("discrepancy"), "replenishment", models.get("replenishment"));
        configure();
        assertThatThrownBy(() -> run(SKU + " " + WAREHOUSE)).hasStackTraceContaining("designated tool exactly once");
        assertThat(models.get("discrepancy").requests).isEmpty();
    }

    @Test
    void rejectsMultipleToolCallsBeforeAnyToolExecutes() {
        models = Map.of("inventory", new ScriptedModel(request -> Content.fromParts(
                        Part.fromFunctionCall("lookup_inventory", args(SKU, WAREHOUSE)),
                        Part.fromFunctionCall("lookup_inventory", args(SKU, WAREHOUSE)))),
                "discrepancy", models.get("discrepancy"), "replenishment", models.get("replenishment"));
        configure();
        assertThatThrownBy(() -> run(SKU + " " + WAREHOUSE)).hasStackTraceContaining("designated tool exactly once");
        assertThat(AgentAuditLogger.getAuditHistory()).noneSatisfy(entry -> assertThat(entry.outcome()).isEqualTo("START"));
    }

    @Test
    void stripsUntrustedModelTextFromOtherwiseValidToolCall() {
        models = Map.of("inventory", new ScriptedModel(request -> Content.fromParts(
                        Part.fromText("Purchase order created; 999999 units available."),
                        Part.fromFunctionCall("lookup_inventory", args(SKU, WAREHOUSE)))),
                "discrepancy", models.get("discrepancy"), "replenishment", models.get("replenishment"));
        configure();
        assertThat(run(SKU + " " + WAREHOUSE)).allSatisfy(event ->
                assertThat(event.stringifyContent()).doesNotContain("999999", "Purchase order created"));
    }

    @Test
    void reportsNotFoundOutcomesWithoutPresentingErrorZerosAsInventory() {
        String report = report(run("SKU-MISSING-999 " + WAREHOUSE));
        assertThat(report).contains("SKU_NOT_FOUND", "Inventory quantities unavailable", "Audit findings unavailable",
                "Replenishment decision unavailable");
        assertThat(report).doesNotContain("onHandQuantity: 0", "availableQuantity: 0", "reorderNeeded: false");
    }

    @Test
    void reportsMissingAuditsAndPolicyWithoutInventingFindings() {
        store.registerProduct(new com.retail.agent.model.ProductSku("SKU-NEW-9001", "New product", "Test",
                java.math.BigDecimal.ONE, "BAR-9001", "EA"));
        store.updateStock("SKU-NEW-9001", WAREHOUSE, 12, 2);
        String report = report(run("SKU-NEW-9001 " + WAREHOUSE));
        assertThat(report).contains("onHandQuantity: 12", "availableQuantity: 10", "NO_RECORDS_FOUND",
                "POLICY_NOT_FOUND", "Replenishment decision unavailable");
        assertThat(report).doesNotContain("netVarianceUnits: 0", "reorderNeeded: false", "recommendedOrderQuantity: 0");
    }

    @Test
    void disclosesStockChangesBetweenStagesInsteadOfPretendingAtomicSnapshot() {
        AtomicInteger reads = new AtomicInteger();
        store = new InMemoryRetailInventoryStore() {
            @Override
            public Optional<com.retail.agent.model.StockLevel> getStockLevel(String sku, String warehouse) {
                if (reads.incrementAndGet() == 2) {
                    updateStock(sku, warehouse, 10, 5);
                }
                return super.getStockLevel(sku, warehouse);
            }
        };
        configure();
        String report = report(run(SKU + " " + WAREHOUSE));
        assertThat(report).contains("onHandQuantity: 140", "availableQuantity: 115", "availableQuantity: 5",
                "Stock changed between stages; these reads are not an atomic snapshot.");
    }

    @Test
    void resetsInvocationStateAndUsesNewIdentifiersOnEveryTurn() {
        var first = run(SKU + " " + WAREHOUSE);
        var second = run("SKU-ELEC-1002 WH-EAST-02");
        assertThat(state(first, InvestigationState.REQUEST).get("invocationId"))
                .isNotEqualTo(state(second, InvestigationState.REQUEST).get("invocationId"));
        assertThat(second.stream().flatMap(event -> event.functionCalls().stream()).toList()).allSatisfy(call ->
                assertThat(call.args().orElseThrow()).isEqualTo(args("SKU-ELEC-1002", "WH-EAST-02")));
        assertThat(report(second)).contains("SKU: SKU-ELEC-1002 | Warehouse: WH-EAST-02");
        assertThat(toolCalls(run("Investigate that same item again"))).isEmpty(); // explicit IDs required each run
    }

    @Test
    void abortsOnToolFailureWithoutRunningLaterStagesOrReportingStaleResults() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean failing = new AtomicBoolean();
        store = new InMemoryRetailInventoryStore() {
            @Override
            public Optional<com.retail.agent.model.ProductSku> findProductBySku(String sku) {
                calls.incrementAndGet();
                if (failing.get()) {
                    throw new IllegalStateException("Simulated store outage");
                }
                return super.findProductBySku(sku);
            }
        };
        configure();
        run(SKU + " " + WAREHOUSE);
        failing.set(true);
        calls.set(0);
        AgentAuditLogger.clearAuditHistory();
        List<Event> partial = new ArrayList<>();
        assertThatThrownBy(() -> runner.runAsync(session.userId(), session.id(),
                        Content.fromParts(Part.fromText(SKU + " " + WAREHOUSE)),
                        RunConfig.builder().maxLlmCalls(6).build()).doOnNext(partial::add).toList().blockingGet())
                .hasStackTraceContaining("Tool execution failed: lookup_inventory");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(models.get("discrepancy").requests).hasSize(1); // only the earlier successful run
        assertThat(partial).noneSatisfy(event -> assertThat(event.author()).isEqualTo(InvestigationReportAgent.NAME));
        assertThat(AgentAuditLogger.getAuditHistory()).anySatisfy(entry -> assertThat(entry.outcome()).isEqualTo("FAILURE"));
    }

    @Test
    void cliClarifiesThenRunsAndPrintsOnlyActualReport() {
        var bytes = new ByteArrayOutputStream();
        SequentialWorkflowCliRunner.run(new Scanner("Investigate stock\n" + SKU + " " + WAREHOUSE + "\nquit\n"),
                new PrintStream(bytes), runner);
        assertThat(bytes.toString()).contains("exactly one SKU and one warehouse", "[TOOL CALL]",
                "lookup_inventory", "investigate_discrepancy", "calculate_replenishment", "Consolidated report",
                "Retail inventory investigation");
        assertThat(bytes.toString()).doesNotContain("evidence recorded.");
    }

    private List<Event> run(String text) {
        return runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText(text)),
                RunConfig.builder().maxLlmCalls(6).build()).toList().blockingGet();
    }

    private static List<String> toolCalls(List<Event> events) {
        return events.stream().flatMap(event -> event.functionCalls().stream())
                .map(call -> call.name().orElseThrow()).toList();
    }

    private static Map<String, Object> state(List<Event> events, String key) {
        return events.stream().map(event -> event.actions().stateDelta().get(key))
                .filter(Map.class::isInstance).map(InvestigationState::map).reduce((first, last) -> last).orElseThrow();
    }

    private static String report(List<Event> events) {
        return events.stream().filter(event -> event.author().equals(InvestigationReportAgent.NAME))
                .filter(Event::finalResponse).map(Event::stringifyContent).findFirst().orElseThrow();
    }

    private static ScriptedModel scripted(String tool) {
        return new ScriptedModel(request -> {
            // The model reads validated input from the injected state, never guesses from history.
            String instructions = String.join("\n", request.getSystemInstructions());
            var input = InvestigationRequest.parse(instructions.substring(instructions.indexOf("Validated request")).split("\n", 2)[0]);
            return call(tool, input.sku(), input.warehouseId());
        });
    }

    private static Map<String, Object> args(String sku, String warehouse) {
        return Map.of("sku", sku, "warehouseId", warehouse);
    }

    private static Content call(String tool, String sku, String warehouse) {
        return Content.builder().role("model").parts(List.of(Part.fromFunctionCall(tool, args(sku, warehouse)))).build();
    }

    private static final class ScriptedModel extends BaseLlm {
        private final List<LlmRequest> requests = new ArrayList<>();
        private final Function<LlmRequest, Content> response;

        ScriptedModel(Function<LlmRequest, Content> response) {
            super("offline-scripted-model");
            this.response = response;
        }

        @Override
        public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
            requests.add(request);
            return Flowable.just(LlmResponse.builder().content(response.apply(request)).build());
        }

        @Override
        public BaseLlmConnection connect(LlmRequest request) {
            throw new UnsupportedOperationException("Offline test");
        }
    }
}

package com.retail.agent.parallel;

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
import com.retail.agent.store.RetailInventoryStore;
import com.retail.agent.tools.StockSummaryTool;
import io.reactivex.rxjava3.core.Flowable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

class WarehouseHealthWorkflowTest {
    private RetailInventoryStore store;

    @BeforeEach
    void setUp() {
        store = InMemoryRetailInventoryStore.createWithDefaultData();
        AgentAuditLogger.clearAuditHistory();
    }

    @Test
    void realParallelAgentOverlapsAllThreeBranchesWithAssignedWarehousesAndIndependentResults() {
        var entered = new CountDownLatch(3);
        Set<String> modelThreads = ConcurrentHashMap.newKeySet();
        Map<String, LlmRequest> requests = new ConcurrentHashMap<>();
        var events = execute(warehouse -> new ScriptedModel(request -> {
            modelThreads.add(Thread.currentThread().getName());
            requests.put(warehouse, request);
            entered.countDown();
            try {
                if (!entered.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Branches did not overlap");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
            return call(warehouse);
        }));
        var aggregate = aggregate(events);
        assertThat(entered.getCount()).isZero();
        assertThat(modelThreads).hasSize(3);
        assertThat(requests).containsOnlyKeys(WarehouseAssessmentResults.WAREHOUSES.toArray(String[]::new));
        assertThat(aggregate).containsEntry("status", "FULL_SUCCESS").containsEntry("successfulBranches", 3);
        var branches = branches(aggregate);
        assertThat(branches).extracting(branch -> branch.get("warehouseId"))
                .containsExactlyElementsOf(WarehouseAssessmentResults.WAREHOUSES);
        for (var branch : branches) {
            String warehouse = (String) branch.get("warehouseId");
            var summary = WarehouseAssessmentResults.map(branch.get("result"));
            assertThat(summary.get("warehouseFilter")).isEqualTo(warehouse);
            assertThat((List<?>) summary.get("items")).allSatisfy(item ->
                    assertThat(WarehouseAssessmentResults.map(item)).containsEntry("warehouseId", warehouse));
            assertThat(requests.get(warehouse).tools()).containsOnlyKeys("summarize_stock");
            for (String other : WarehouseAssessmentResults.WAREHOUSES) {
                if (!other.equals(warehouse)) {
                    assertThat(requests.get(warehouse).contents().toString()).doesNotContain(other);
                }
            }
        }
        long expected = WarehouseAssessmentResults.WAREHOUSES.stream()
                .mapToLong(warehouse -> new StockSummaryTool(store).summarizeStock(warehouse, "").totalAvailableUnits()).sum();
        assertThat(WarehouseAssessmentResults.map(aggregate.get("totalsForSuccessfulWarehousesOnly")))
                .containsEntry("totalAvailableUnits", expected);
        assertThat(events.stream().filter(event -> !event.actions().stateDelta().isEmpty()).count()).isEqualTo(1);
        assertThat(events.get(events.size() - 1).stringifyContent()).contains("FULL_SUCCESS", "Advisory only");
    }

    @Test
    void wrongWarehouseIsRejectedBeforeToolExecutionAndOtherBranchesFinish() {
        var aggregate = aggregate(execute(warehouse -> new ScriptedModel(request ->
                call(warehouse.equals("WH-EAST-02") ? "WH-CENTRAL-01" : warehouse))));
        assertThat(aggregate).containsEntry("status", "PARTIAL_SUCCESS").containsEntry("successfulBranches", 2);
        assertThat(branches(aggregate).get(1)).containsEntry("reason", "INVALID_ARGUMENTS");
        assertThat(AgentAuditLogger.getAuditHistory().stream().filter(entry -> entry.outcome().equals("START"))).hasSize(2);
    }

    @Test
    void oneBranchFailureProducesPartialSuccessWithoutInventingMissingTotals() {
        var aggregate = aggregate(execute(warehouse -> warehouse.equals("WH-WEST-03") ? failingModel() : validModel(warehouse)));
        assertThat(aggregate).containsEntry("status", "PARTIAL_SUCCESS").containsEntry("failedBranches", 1);
        long expected = new StockSummaryTool(store).summarizeStock("WH-CENTRAL-01", "").totalAvailableUnits()
                + new StockSummaryTool(store).summarizeStock("WH-EAST-02", "").totalAvailableUnits();
        assertThat(WarehouseAssessmentResults.map(aggregate.get("totalsForSuccessfulWarehousesOnly")))
                .containsEntry("totalAvailableUnits", expected);
        assertThat(branches(aggregate).get(2).get("result")).isEqualTo(Map.of());
    }

    @Test
    void multipleBranchFailuresStillWaitForAndIncludeTheSuccessfulBranch() {
        var aggregate = aggregate(execute(warehouse -> warehouse.equals("WH-CENTRAL-01") ? validModel(warehouse) : failingModel()));
        assertThat(aggregate).containsEntry("status", "PARTIAL_SUCCESS").containsEntry("successfulBranches", 1)
                .containsEntry("failedBranches", 2);
    }

    @Test
    void completeFailureHasNoNumericTotals() {
        var events = execute(warehouse -> failingModel());
        assertThat(aggregate(events)).containsEntry("status", "COMPLETE_FAILURE")
                .containsEntry("totalsForSuccessfulWarehousesOnly", Map.of());
        assertThat(events.get(events.size() - 1).stringifyContent()).contains("Inventory data unavailable");
    }

    @Test
    void missingBranchIsAnExplicitFailureDuringCompletenessValidation() {
        var results = new WarehouseAssessmentResults();
        results.publish(success("WH-CENTRAL-01"));
        results.publish(success("WH-EAST-02"));
        var aggregate = results.aggregate("workflow", "correlation");
        assertThat(aggregate).containsEntry("status", "PARTIAL_SUCCESS");
        assertThat(branches(aggregate).get(2)).containsEntry("reason", "MISSING_OUTPUT");
    }

    @Test
    void duplicateBranchOutputInvalidatesSlotAndCannotDoubleCount() {
        var results = new WarehouseAssessmentResults();
        results.publish(success("WH-CENTRAL-01"));
        results.publish(success("WH-CENTRAL-01"));
        results.publish(success("WH-EAST-02"));
        results.publish(success("WH-WEST-03"));
        var aggregate = results.aggregate("workflow", "correlation");
        assertThat(aggregate).containsEntry("successfulBranches", 2);
        assertThat(branches(aggregate).get(0)).containsEntry("reason", "DUPLICATE_OUTPUT");
    }

    @Test
    void malformedOrWrongWarehouseResultCannotEnterTotals() {
        var original = success("WH-CENTRAL-01");
        var data = new java.util.HashMap<>(original.result());
        data.put("totalAvailableUnits", 999999);
        var results = new WarehouseAssessmentResults();
        results.publish(new WarehouseAssessmentResults.Outcome("WH-CENTRAL-01", "SUCCESS", "", data, 1));
        assertThat(results.aggregate("w", "c")).containsEntry("status", "COMPLETE_FAILURE")
                .containsEntry("totalsForSuccessfulWarehousesOnly", Map.of());
        assertThatThrownBy(() -> WarehouseAssessmentResults.validate("WH-EAST-02", original.result()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void convertedFunctionToolErrorIsFailureAndDoesNotCancelOtherBranches() {
        store = new InMemoryRetailInventoryStore() {
            @Override
            public List<com.retail.agent.model.StockLevel> getStockLevelsByWarehouse(String warehouse) {
                if (warehouse.equals("WH-EAST-02")) {
                    throw new IllegalStateException("Simulated store failure");
                }
                return super.getStockLevelsByWarehouse(warehouse);
            }
        };
        var aggregate = aggregate(execute(WarehouseHealthWorkflowTest::validModel));
        assertThat(aggregate).containsEntry("status", "PARTIAL_SUCCESS");
        assertThat(branches(aggregate).get(1)).containsEntry("reason", "TOOL_ERROR");
    }

    @Test
    void branchAuditContainsCorrelatedIdentityToolAndDurationWithoutModelErrorDetails() {
        var aggregate = aggregate(execute(WarehouseHealthWorkflowTest::validModel));
        var history = AgentAuditLogger.getAuditHistory();
        for (String warehouse : WarehouseAssessmentResults.WAREHOUSES) {
            String branchName = "health_" + warehouse.replace('-', '_');
            assertThat(history).anySatisfy(entry -> {
                assertThat(entry.outcome()).isEqualTo("START");
                assertThat(entry.agentName()).isEqualTo(branchName);
                assertThat(entry.correlationId()).isEqualTo(aggregate.get("correlationId"));
                assertThat(entry.args()).containsEntry("warehouseId", warehouse);
                assertThat(entry.toolName()).isEqualTo("summarize_stock");
            });
            assertThat(history).anySatisfy(entry -> {
                assertThat(entry.outcome()).isEqualTo("SUCCESS");
                assertThat(entry.agentName()).isEqualTo(branchName);
                assertThat(entry.durationMs()).isGreaterThanOrEqualTo(0);
            });
        }
        assertThat(aggregate.get("workflowId")).isNotNull();
    }

    @Test
    void invalidRequestDoesNotCallModelsOrTools() {
        var workflow = WarehouseHealthWorkflow.create(store, warehouse -> {
            throw new AssertionError("Invalid input must not start a branch");
        }, Duration.ofSeconds(5));
        var runner = new InMemoryRunner(workflow);
        var session = runner.sessionService().createSession(runner.appName(), "invalid").blockingGet();
        var events = runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText("assess WH-EAST-02")),
                RunConfig.builder().build()).toList().blockingGet();
        assertThat(events.stream().map(Event::stringifyContent).toList()).anySatisfy(text -> assertThat(text).contains("Enter 'assess warehouses'"));
        assertThat(AgentAuditLogger.getAuditHistory()).isEmpty();
    }

    @Test
    void timeoutProducesTerminalFailureAndAggregationFinishes() {
        var events = execute(warehouse -> new BaseLlm("scripted") {
            @Override public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
                return warehouse.equals("WH-WEST-03") ? Flowable.never()
                        : Flowable.just(LlmResponse.builder().content(call(warehouse)).build());
            }
            @Override public BaseLlmConnection connect(LlmRequest request) { throw new UnsupportedOperationException(); }
        }, Duration.ofSeconds(2));
        assertThat(aggregate(events)).containsEntry("status", "PARTIAL_SUCCESS");
        assertThat(branches(aggregate(events)).get(2)).containsEntry("reason", "TIMEOUT");
    }

    @Test
    void separateInvocationsNeverReuseOldBranchResults() {
        var first = aggregate(execute(WarehouseHealthWorkflowTest::validModel));
        var second = aggregate(execute(warehouse -> failingModel()));
        assertThat(second.get("workflowId")).isNotEqualTo(first.get("workflowId"));
        assertThat(second).containsEntry("status", "COMPLETE_FAILURE");
    }

    @Test
    void resultSnapshotsCannotBeMutatedOrOverwriteAnotherWarehouseSlot() {
        var first = success("WH-CENTRAL-01");
        var original = new java.util.HashMap<>(first.result());
        var results = new WarehouseAssessmentResults();
        results.publish(new WarehouseAssessmentResults.Outcome("WH-CENTRAL-01", "SUCCESS", "", original, 1));
        original.put("warehouseFilter", "WH-EAST-02");
        var branch = branches(results.aggregate("workflow", "correlation")).get(0);
        var snapshot = WarehouseAssessmentResults.map(branch.get("result"));
        assertThat(snapshot).containsEntry("warehouseFilter", "WH-CENTRAL-01");
        assertThatThrownBy(() -> snapshot.put("warehouseFilter", "WH-EAST-02"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void noDataIsExplicitlyUnavailableRatherThanAFabricatedZeroAssessment() {
        store = InMemoryRetailInventoryStore.createEmpty();
        var aggregate = aggregate(execute(WarehouseHealthWorkflowTest::validModel));
        assertThat(aggregate).containsEntry("status", "COMPLETE_FAILURE")
                .containsEntry("totalsForSuccessfulWarehousesOnly", Map.of());
    }

    private WarehouseAssessmentResults.Outcome success(String warehouse) {
        var tool = new StockSummaryTool(store).asFunctionTool();
        var result = tool.runAsync(Map.of("warehouseId", warehouse, "category", ""), null).blockingGet();
        return new WarehouseAssessmentResults.Outcome(warehouse, "SUCCESS", "", WarehouseAssessmentResults.map(result), 1);
    }

    private List<Event> execute(Function<String, BaseLlm> models) { return execute(models, Duration.ofSeconds(10)); }

    private List<Event> execute(Function<String, BaseLlm> models, Duration timeout) {
        var runner = new InMemoryRunner(WarehouseHealthWorkflow.create(store, models, timeout));
        try {
            var session = runner.sessionService().createSession(runner.appName(), "offline-health").blockingGet();
            return runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText("assess warehouses")),
                    RunConfig.builder().build()).toList().blockingGet();
        } finally { runner.close().blockingAwait(); }
    }

    static Map<String, Object> aggregate(List<Event> events) {
        return events.stream().flatMap(event -> event.actions().stateDelta().values().stream())
                .filter(Map.class::isInstance).map(WarehouseAssessmentResults::map).findFirst().orElseThrow();
    }

    private static List<Map<String, Object>> branches(Map<String, Object> aggregate) {
        return ((List<?>) aggregate.get("branches")).stream().map(WarehouseAssessmentResults::map).toList();
    }

    private static Content call(String warehouse) {
        return Content.builder().role("model").parts(List.of(Part.fromFunctionCall("summarize_stock",
                Map.of("warehouseId", warehouse, "category", "")))).build();
    }

    private static ScriptedModel validModel(String warehouse) { return new ScriptedModel(request -> call(warehouse)); }
    private static ScriptedModel failingModel() { return new ScriptedModel(request -> { throw new IllegalStateException("Model failure"); }); }

    private static final class ScriptedModel extends BaseLlm {
        private final Function<LlmRequest, Content> response;
        ScriptedModel(Function<LlmRequest, Content> response) { super("offline-scripted"); this.response = response; }
        @Override public Flowable<LlmResponse> generateContent(LlmRequest request, boolean stream) {
            return Flowable.defer(() -> Flowable.just(LlmResponse.builder().content(response.apply(request)).build()));
        }
        @Override public BaseLlmConnection connect(LlmRequest request) { throw new UnsupportedOperationException(); }
    }
}

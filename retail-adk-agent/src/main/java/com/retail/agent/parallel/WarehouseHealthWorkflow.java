package com.retail.agent.parallel;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.InvocationContext;
import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.ParallelAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.LlmResponse;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCallingConfig;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.Part;
import com.google.genai.types.ToolConfig;
import com.retail.agent.RetailInventoryAgent;
import com.retail.agent.observability.AgentAuditLogger;
import com.retail.agent.store.RetailInventoryStore;
import com.retail.agent.tools.StockSummaryTool;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Validate -> actual ADK ParallelAgent -> one serial, deterministic aggregation. */
public final class WarehouseHealthWorkflow extends BaseAgent {
    public static final String NAME = "warehouse_health_workflow";
    public static final String MODEL_NAME = RetailInventoryAgent.MODEL_NAME;
    public static final String RESULT_PREFIX = "temp:warehouse_health:";
    private static final Logger LOG = LoggerFactory.getLogger(WarehouseHealthWorkflow.class);
    private final RetailInventoryStore store;
    private final Function<String, BaseLlm> models;
    private final Duration timeout;

    private WarehouseHealthWorkflow(RetailInventoryStore store, Function<String, BaseLlm> models, Duration timeout) {
        super(NAME, "Parallel assessment of the three configured warehouses", List.of(), List.of(), List.of());
        this.store = Objects.requireNonNull(store);
        this.models = Objects.requireNonNull(models);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Branch timeout must be positive");
        }
    }

    public static WarehouseHealthWorkflow create(RetailInventoryStore store) {
        return new WarehouseHealthWorkflow(store, warehouse -> null, Duration.ofSeconds(60));
    }

    static WarehouseHealthWorkflow create(RetailInventoryStore store, Function<String, BaseLlm> models, Duration timeout) {
        return new WarehouseHealthWorkflow(store, models, timeout);
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext ctx) {
        return Flowable.defer(() -> {
            String prompt = ctx.userContent().stream().flatMap(content -> content.parts().orElse(List.of()).stream())
                    .flatMap(part -> part.text().stream()).collect(Collectors.joining(" ")).trim();
            if (!prompt.equalsIgnoreCase("assess warehouses") && !prompt.equalsIgnoreCase("assess warehouse health")) {
                return Flowable.just(event(ctx, "Enter 'assess warehouses' to assess WH-CENTRAL-01, WH-EAST-02 "
                        + "and WH-WEST-03. Individual warehouse/category filters are not supported.", new EventActions()));
            }
            String workflowId = ctx.newUuid();
            long workflowStart = System.nanoTime();
            var results = new WarehouseAssessmentResults();
            AgentAuditLogger.logAgentStart(ctx.session().id(), ctx.invocationId(), name());
            audit(ctx, workflowId, "ROOT", "ALL", "WORKFLOW_START", 0);
            var branches = WarehouseAssessmentResults.WAREHOUSES.stream()
                    .map(warehouse -> new WarehouseBranch(warehouse, ctx, workflowId, results)).toList();
            var parallel = ParallelAgent.builder().name("parallel_warehouse_assessment")
                    .description("Three isolated warehouse assessments")
                    .subAgents(branches).scheduler(Schedulers.io()).build();
            // Nested branch events are not forwarded into the parent session. Branches use private sessions.
            return parallel.runAsync(ctx).ignoreElements().andThen(Flowable.defer(() -> {
                Map<String, Object> aggregate = results.aggregate(workflowId, ctx.invocationId());
                var actions = new EventActions();
                actions.stateDelta().put(RESULT_PREFIX + workflowId + ":assessment", aggregate);
                audit(ctx, workflowId, "ROOT", "ALL", (String) aggregate.get("status"), elapsed(workflowStart));
                AgentAuditLogger.logAgentEnd(ctx.session().id(), ctx.invocationId(), name());
                return Flowable.just(event(ctx, render(aggregate), actions));
            }));
        });
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext ctx) {
        return Flowable.error(new UnsupportedOperationException("Use text runAsync"));
    }

    private final class WarehouseBranch extends BaseAgent {
        private final String warehouse;
        private final InvocationContext parent;
        private final String workflowId;
        private final WarehouseAssessmentResults results;

        WarehouseBranch(String warehouse, InvocationContext parent, String workflowId, WarehouseAssessmentResults results) {
            super("health_" + warehouse.replace('-', '_'), "Assessment restricted to " + warehouse,
                    List.of(), List.of(), List.of());
            this.warehouse = warehouse;
            this.parent = parent;
            this.workflowId = workflowId;
            this.results = results;
        }

        @Override
        protected Flowable<Event> runAsyncImpl(InvocationContext ctx) {
            return Flowable.defer(() -> {
                long start = System.nanoTime();
                audit(parent, workflowId, name(), warehouse, "BRANCH_START", 0);
                AgentAuditLogger.logAgentStart(parent.session().id(), parent.invocationId(), name());
                // These references and all LLM/tool callback state belong to this branch invocation only.
                var result = new AtomicReference<Map<String, Object>>();
                var toolStart = new java.util.concurrent.atomic.AtomicLong();
                return Flowable.using(() -> new InMemoryRunner(agent(result, toolStart)), privateRunner ->
                        privateRunner.sessionService().createSession(privateRunner.appName(), "health-" + workflowId)
                                .flatMapPublisher(session -> privateRunner.runAsync(session.userId(), session.id(),
                                        Content.fromParts(Part.fromText("Summarize " + warehouse + " for all categories.")),
                                        RunConfig.builder().maxLlmCalls(3).build()))
                                .ignoreElements().andThen(Flowable.fromCallable(() -> {
                                    if (result.get() == null) {
                                        throw new BranchFailure("MISSING_OUTPUT");
                                    }
                                    return new WarehouseAssessmentResults.Outcome(warehouse, "SUCCESS", "",
                                            result.get(), elapsed(start));
                                })), privateRunner -> privateRunner.close().blockingAwait())
                        // Absolute deadline, not an event-inactivity timeout. Recovery occurs before merge.
                        .takeUntil(Flowable.timer(timeout.toMillis(), TimeUnit.MILLISECONDS)
                                .flatMap(tick -> Flowable.error(new BranchFailure("TIMEOUT"))))
                        .onErrorReturn(failure -> WarehouseAssessmentResults.Outcome.failure(warehouse,
                                failure instanceof BranchFailure known ? known.code : "BRANCH_ERROR", elapsed(start)))
                        .map(outcome -> {
                            results.publish(outcome);
                            if (!outcome.status().equals("SUCCESS") && toolStart.get() != 0) {
                                AgentAuditLogger.logToolError(parent.session().id(), parent.invocationId(), name(),
                                        "summarize_stock", elapsed(toolStart.get()), new IllegalStateException(outcome.reason()));
                            }
                            audit(parent, workflowId, name(), warehouse, outcome.status() + ":" + outcome.reason(), outcome.durationMs());
                            AgentAuditLogger.logAgentEnd(parent.session().id(), parent.invocationId(), name());
                            return Event.builder().author(name()).invocationId(ctx.invocationId())
                                    .content(Content.fromParts(Part.fromText("Warehouse branch reached terminal state."))).build();
                        });
            });
        }

        private LlmAgent agent(AtomicReference<Map<String, Object>> result, java.util.concurrent.atomic.AtomicLong toolStart) {
            var builder = LlmAgent.builder().name(name() + "_analyst").model(MODEL_NAME)
                    .instruction("Call summarize_stock exactly once with warehouseId='" + warehouse
                            + "' and category=''. Do not change warehouses or filter categories. Never create purchase orders.")
                    .includeContents(LlmAgent.IncludeContents.NONE)
                    .disallowTransferToParent(true).disallowTransferToPeers(true)
                    .tools(new StockSummaryTool(store).asFunctionTool())
                    .beforeModelCallbackSync((ctx, request) -> {
                        if (result.get() != null) {
                            return Optional.of(LlmResponse.builder().content(Content.fromParts(Part.fromText("Evidence recorded."))).build());
                        }
                        var config = request.config().map(GenerateContentConfig::toBuilder).orElseGet(GenerateContentConfig::builder);
                        request.config(config.toolConfig(ToolConfig.builder().functionCallingConfig(
                                FunctionCallingConfig.builder().mode("ANY").allowedFunctionNames("summarize_stock").build()).build()).build());
                        return Optional.empty();
                    })
                    .afterModelCallbackSync((ctx, response) -> {
                        var calls = response.content().stream().flatMap(content -> content.parts().orElse(List.of()).stream())
                                .flatMap(part -> part.functionCall().stream()).toList();
                        if (calls.size() != 1 || !"summarize_stock".equals(calls.get(0).name().orElse(""))) {
                            throw new BranchFailure("INVALID_TOOL_CALL");
                        }
                        arguments(calls.get(0).args().orElse(Map.of()));
                        return Optional.of(response.toBuilder().content(Content.builder().role("model")
                                .parts(response.content().orElseThrow().parts().orElseThrow().stream()
                                        .filter(part -> part.functionCall().isPresent()).toList()).build()).build());
                    })
                    .beforeToolCallbackSync((ctx, tool, args, toolCtx) -> {
                        arguments(args);
                        if (!tool.name().equals("summarize_stock") || result.get() != null) {
                            throw new BranchFailure("DUPLICATE_OUTPUT");
                        }
                        toolStart.set(System.nanoTime());
                        AgentAuditLogger.logToolStart(parent.session().id(), parent.invocationId(), name(), tool.name(),
                                Map.of("warehouseId", warehouse, "category", ""));
                        audit(parent, workflowId, name(), warehouse, "TOOL_START:summarize_stock", 0);
                        return Optional.empty();
                    })
                    .afterToolCallbackSync((ctx, tool, args, toolCtx, raw) -> {
                        var data = WarehouseAssessmentResults.map(raw);
                        if (!"SUCCESS".equals(data.get("status"))) {
                            throw new BranchFailure("NO_DATA".equals(data.get("status")) ? "NO_DATA" : "TOOL_ERROR");
                        }
                        try {
                            WarehouseAssessmentResults.validate(warehouse, data);
                        } catch (RuntimeException invalid) {
                            throw new BranchFailure("INVALID_RESULT");
                        }
                        var snapshot = new WarehouseAssessmentResults.Outcome(warehouse, "SUCCESS", "", data, 0).result();
                        if (!result.compareAndSet(null, snapshot)) {
                            throw new BranchFailure("DUPLICATE_OUTPUT");
                        }
                        toolCtx.state().put(RESULT_PREFIX + warehouse, snapshot); // private session only
                        double duration = elapsed(toolStart.get());
                        AgentAuditLogger.logToolSuccess(parent.session().id(), parent.invocationId(), name(), tool.name(), duration, data);
                        audit(parent, workflowId, name(), warehouse, "TOOL_SUCCESS:summarize_stock", duration);
                        return Optional.empty();
                    });
            BaseLlm model = models.apply(warehouse);
            if (model != null) {
                builder.model(model);
            }
            return builder.build();
        }

        private void arguments(Map<String, Object> args) {
            if (!args.equals(Map.of("warehouseId", warehouse, "category", ""))
                    && !args.equals(Map.of("warehouseId", warehouse))) {
                throw new BranchFailure("INVALID_ARGUMENTS");
            }
        }

        @Override
        protected Flowable<Event> runLiveImpl(InvocationContext ctx) {
            return Flowable.error(new UnsupportedOperationException("Use text runAsync"));
        }
    }

    private static Event event(InvocationContext ctx, String text, EventActions actions) {
        return Event.builder().author(NAME).invocationId(ctx.invocationId()).actions(actions)
                .content(Content.fromParts(Part.fromText(text))).build();
    }

    static String render(Map<String, Object> aggregate) {
        var report = new StringBuilder("Warehouse health assessment — in-memory demonstration data\n")
                .append("Workflow: ").append(aggregate.get("workflowId")).append("\nStatus: ").append(aggregate.get("status"));
        for (Object raw : (List<?>) aggregate.get("branches")) {
            var branch = WarehouseAssessmentResults.map(raw);
            report.append("\n\n").append(branch.get("warehouseId")).append(": ").append(branch.get("status"));
            if (branch.get("status").equals("SUCCESS")) {
                var summary = WarehouseAssessmentResults.map(branch.get("result"));
                WarehouseAssessmentResults.METRICS.forEach(metric -> report.append("\n").append(metric).append(": ").append(summary.get(metric)));
            } else {
                report.append(" — ").append(branch.get("reason")).append(". Inventory data unavailable.");
            }
        }
        return report.append("\n\nTotals for successful warehouses only: ").append(aggregate.get("totalsForSuccessfulWarehousesOnly"))
                .append("\nHealth counts follow summarize_stock: healthyStockCount includes UNMONITORED items.")
                .append("\nReads are concurrent, not an atomic inventory snapshot. Advisory only; no purchase orders created.").toString();
    }

    private static double elapsed(long start) {
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static void audit(InvocationContext ctx, String workflow, String branch, String warehouse, String outcome, double duration) {
        LOG.info("[WAREHOUSE-HEALTH] Workflow={} Correlation={} Branch={} Warehouse={} Outcome={} DurationMs={}",
                workflow, ctx.invocationId(), branch, warehouse, outcome, duration);
    }

    private static final class BranchFailure extends RuntimeException {
        final String code;
        BranchFailure(String code) { super(code); this.code = code; }
    }
}

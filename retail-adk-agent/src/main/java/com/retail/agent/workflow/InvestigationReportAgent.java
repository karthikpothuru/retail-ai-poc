package com.retail.agent.workflow;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.retail.agent.multiagent.MultiAgentObservabilityCallbacks;
import com.retail.agent.tools.ReplenishmentCalculatorTool;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;

import java.util.List;
import java.util.Map;

/** Renders only validated tool values. No model can add facts to the consolidated report. */
final class InvestigationReportAgent extends BaseAgent {
    static final String NAME = "investigation_report";

    InvestigationReportAgent() {
        super(NAME, "Consolidated factual report from structured workflow state", List.of(),
                List.of(ctx -> Maybe.fromOptional(MultiAgentObservabilityCallbacks.beforeAgent().call(ctx))),
                List.of(ctx -> Maybe.fromOptional(MultiAgentObservabilityCallbacks.afterAgent().call(ctx))));
    }

    @Override
    protected Flowable<Event> runAsyncImpl(InvocationContext invocation) {
        return Flowable.defer(() -> {
            var actions = new EventActions();
            var ctx = new CallbackContext(invocation, actions);
            InvestigationState.requirePreceding(ctx, 3);
            var request = InvestigationState.request(ctx);
            var inventory = InvestigationState.result(ctx, "inventory");
            var discrepancy = InvestigationState.result(ctx, "discrepancy");
            var replenishment = InvestigationState.result(ctx, "replenishment");
            String report = render(request, inventory, discrepancy, replenishment);
            ctx.state().put(InvestigationState.REPORT, report);
            return Flowable.just(Event.builder().author(name()).invocationId(invocation.invocationId())
                    .content(Content.fromParts(Part.fromText(report))).actions(actions).build());
        });
    }

    @Override
    protected Flowable<Event> runLiveImpl(InvocationContext invocation) {
        return Flowable.error(new UnsupportedOperationException("Use standard runAsync for this text workflow."));
    }

    private static String render(Map<String, Object> request, Map<String, Object> inventory,
                                 Map<String, Object> discrepancy, Map<String, Object> replenishment) {
        StringBuilder report = new StringBuilder("Retail inventory investigation — in-memory demonstration data\n")
                .append("SKU: ").append(request.get("sku")).append(" | Warehouse: ")
                .append(request.get("warehouseId")).append("\n\nInventory\nStatus: ").append(inventory.get("status"));
        if ("FOUND".equals(inventory.get("status"))) {
            fields(report, inventory, "onHandQuantity", "reservedQuantity", "availableQuantity");
        } else {
            report.append("\nInventory quantities unavailable. ").append(inventory.get("message"));
        }
        report.append("\n\nDiscrepancy investigation\nStatus: ").append(discrepancy.get("status"));
        if ("FOUND".equals(discrepancy.get("status"))) {
            fields(report, discrepancy, "totalAuditCount", "matchingAuditCount", "shrinkageAuditCount",
                    "surplusAuditCount", "netVarianceUnits");
            for (Object record : (List<?>) discrepancy.get("auditRecords")) {
                var audit = InvestigationState.map(record);
                report.append("\nRecorded audit evidence:");
                fields(report, audit, "recordId", "timestamp", "auditorId", "physicalCount",
                        "systemExpectedQuantity", "variance", "classification", "documentedNotes");
            }
            report.append("\nNo discrepancy cause is inferred beyond the recorded notes.");
        } else {
            report.append("\nAudit findings unavailable. ").append(discrepancy.get("summaryEvidence"));
        }
        report.append("\n\nReplenishment assessment\nStatus: ").append(replenishment.get("status"));
        if ("SUCCESS".equals(replenishment.get("status"))) {
            fields(report, replenishment, "availableQuantity", "reorderPoint", "safetyStock", "leadTimeDays",
                    "reorderNeeded", "safetyStockBreached", "recommendedOrderQuantity", "urgencyLevel");
            if ("FOUND".equals(inventory.get("status"))
                    && !inventory.get("availableQuantity").equals(replenishment.get("availableQuantity"))) {
                report.append("\nStock changed between stages; these reads are not an atomic snapshot.");
            }
        } else {
            report.append("\nReplenishment decision unavailable. ").append(replenishment.get("message"));
        }
        return report.append("\n\n").append(ReplenishmentCalculatorTool.ORDER_STATUS_DISCLAIMER).toString();
    }

    private static void fields(StringBuilder target, Map<String, Object> values, String... fields) {
        for (String field : fields) {
            target.append("\n").append(field).append(": ").append(values.get(field));
        }
    }
}

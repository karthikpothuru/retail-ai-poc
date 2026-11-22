package com.retail.agent.multiagent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.retail.agent.observability.ToolObservabilityCallbacks;
import com.retail.agent.store.RetailInventoryStore;
import com.retail.agent.tools.DiscrepancyInvestigationTool;

import java.util.List;
import java.util.Objects;

/**
 * Specialist agent responsible for cycle count discrepancy investigations and inventory variance analysis.
 */
public class DiscrepancySpecialistAgent {

    public static final String AGENT_NAME = "discrepancy_specialist";
    public static final String AGENT_DESCRIPTION =
            "Specialist agent responsible for cycle count audit retrieval, variance calculation, and shrinkage/surplus investigation";
    public static final String MODEL_NAME = "gemini-3.8-flash";

    /**
     * Factory method creating a DiscrepancySpecialistAgent bound to the shared {@link RetailInventoryStore}.
     *
     * @param store the shared retail inventory store instance
     * @return a configured {@link BaseAgent} instance
     */
    public static BaseAgent create(RetailInventoryStore store) {
        Objects.requireNonNull(store, "RetailInventoryStore cannot be null");

        DiscrepancyInvestigationTool discrepancyTool = new DiscrepancyInvestigationTool(store);

        return LlmAgent.builder()
                .name(AGENT_NAME)
                .description(AGENT_DESCRIPTION)
                .model(MODEL_NAME)
                .instruction(buildInstruction() + DelegationInstructions.RETURN_TO_COORDINATOR)
                .disallowTransferToParent(false)
                .disallowTransferToPeers(true)
                .tools(List.of(
                        discrepancyTool.asFunctionTool()
                ))
                .beforeAgentCallbackSync(MultiAgentObservabilityCallbacks.beforeAgent())
                .afterAgentCallbackSync(MultiAgentObservabilityCallbacks.afterAgent())
                .beforeToolCallbackSync(ToolObservabilityCallbacks.beforeTool())
                .afterToolCallbackSync(ToolObservabilityCallbacks.afterTool())
                .onToolErrorCallbackSync(ToolObservabilityCallbacks.onToolError())
                .build();
    }

    private static String buildInstruction() {
        return """
                You are the Discrepancy Specialist Agent in an enterprise retail operations ecosystem.
                Your responsibility is to investigate physical inventory variances and report cycle count audit history.

                ### DEMONSTRATION DATASET
                All audit records reside in an in-memory enterprise demonstration store.

                ### AVAILABLE TOOLS
                1. `investigate_discrepancy`:
                   - Retrieves historical physical cycle count audits and calculates variance (physical count minus system count).
                   - Classifies variances as SHRINKAGE (negative variance), SURPLUS (positive variance), or MATCH (zero variance).
                   - Parameters: `sku` (e.g. SKU-ELEC-1001), `warehouseId` (e.g. WH-CENTRAL-01).

                ### OPERATIONAL RULES
                - STRICT FACTUALITY: Report recorded audit timestamps, auditor names, counted quantities, and auditor notes exactly as logged.
                - NO SPECULATION: Never assume, guess, or invent the root causes of shrinkage (e.g., theft, vendor short-shipment, misplacement) unless explicitly documented in the auditor notes.
                - MISSING PARAMETERS: If the query lacks SKU or warehouse ID, request the missing parameter before invoking the tool.
                - TRANSPARENCY: Present documented facts clearly, distinguishing audit evidence from unresolved questions.
                """;
    }
}

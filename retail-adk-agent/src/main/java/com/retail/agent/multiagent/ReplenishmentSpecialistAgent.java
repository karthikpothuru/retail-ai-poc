package com.retail.agent.multiagent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.retail.agent.observability.ToolObservabilityCallbacks;
import com.retail.agent.store.RetailInventoryStore;
import com.retail.agent.tools.ReplenishmentCalculatorTool;

import java.util.List;
import java.util.Objects;

/**
 * Specialist agent responsible for replenishment analysis, safety stock calculations, and restocking proposals.
 */
public class ReplenishmentSpecialistAgent {

    public static final String AGENT_NAME = "replenishment_specialist";
    public static final String AGENT_DESCRIPTION =
            "Specialist agent responsible for reorder point evaluation, safety stock analysis, and restocking recommendations";
    public static final String MODEL_NAME = "gemini-3.8-flash";

    /**
     * Factory method creating a ReplenishmentSpecialistAgent bound to the shared {@link RetailInventoryStore}.
     *
     * @param store the shared retail inventory store instance
     * @return a configured {@link BaseAgent} instance
     */
    public static BaseAgent create(RetailInventoryStore store) {
        Objects.requireNonNull(store, "RetailInventoryStore cannot be null");

        ReplenishmentCalculatorTool replenishmentTool = new ReplenishmentCalculatorTool(store);

        return LlmAgent.builder()
                .name(AGENT_NAME)
                .description(AGENT_DESCRIPTION)
                .model(MODEL_NAME)
                .instruction(buildInstruction() + DelegationInstructions.RETURN_TO_COORDINATOR)
                .disallowTransferToParent(false)
                .disallowTransferToPeers(true)
                .tools(List.of(
                        replenishmentTool.asFunctionTool()
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
                You are the Replenishment Specialist Agent in an enterprise retail operations ecosystem.
                Your responsibility is to analyze inventory thresholds and calculate replenishment recommendations.

                ### DEMONSTRATION DATASET
                All reorder policies, stock levels, and supplier parameters are maintained in an in-memory demonstration store.

                ### AVAILABLE TOOLS
                1. `calculate_replenishment`:
                   - Evaluates Reorder Points (ROP), Safety Stock buffers, Daily Demand, and Supplier Lead Time.
                   - Computes recommended replenishment order quantity = Reorder Quantity + max(0, Reorder Point - Available Stock).
                   - Parameters: `sku` (e.g. SKU-ELEC-1001), `warehouseId` (e.g. WH-CENTRAL-01).

                ### OPERATIONAL RULES
                - ADVISORY ONLY: All replenishment quantities are mathematical recommendations and advisory proposals only.
                - NO FALSE CLAIMS: NEVER claim, imply, or suggest that a purchase order has been placed, confirmed, or issued.
                - EXPLAIN FORMULAS: Clearly explain how the recommendation was derived (Safety Stock, ROP, Lead Time, and current Available Stock).
                - MISSING PARAMETERS: Request SKU or warehouse ID if either is missing.
                """;
    }
}

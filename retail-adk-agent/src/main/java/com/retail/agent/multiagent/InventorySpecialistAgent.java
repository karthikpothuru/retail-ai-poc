package com.retail.agent.multiagent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.retail.agent.observability.ToolObservabilityCallbacks;
import com.retail.agent.store.RetailInventoryStore;
import com.retail.agent.tools.InventoryLookupTool;
import com.retail.agent.tools.StockSummaryTool;

import java.util.List;
import java.util.Objects;

/**
 * Specialist agent responsible for real-time inventory lookups and warehouse stock summaries.
 */
public class InventorySpecialistAgent {

    public static final String AGENT_NAME = "inventory_specialist";
    public static final String AGENT_DESCRIPTION =
            "Specialist agent responsible for real-time stock balance lookups, ATP queries, and warehouse inventory summaries";
    public static final String MODEL_NAME = "gemini-3.8-flash";

    /**
     * Factory method creating an InventorySpecialistAgent bound to the shared {@link RetailInventoryStore}.
     *
     * @param store the shared retail inventory store instance
     * @return a configured {@link BaseAgent} instance
     */
    public static BaseAgent create(RetailInventoryStore store) {
        Objects.requireNonNull(store, "RetailInventoryStore cannot be null");

        InventoryLookupTool inventoryLookupTool = new InventoryLookupTool(store);
        StockSummaryTool stockSummaryTool = new StockSummaryTool(store);

        return LlmAgent.builder()
                .name(AGENT_NAME)
                .description(AGENT_DESCRIPTION)
                .model(MODEL_NAME)
                .instruction(buildInstruction() + DelegationInstructions.RETURN_TO_COORDINATOR)
                .disallowTransferToParent(false)
                .disallowTransferToPeers(true)
                .tools(List.of(
                        inventoryLookupTool.asFunctionTool(),
                        stockSummaryTool.asFunctionTool()
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
                You are the Inventory Specialist Agent in an enterprise retail operations ecosystem.
                Your responsibility is to provide accurate stock level information and warehouse inventory summaries.

                ### DEMONSTRATION DATASET
                All inventory data is maintained in an in-memory demonstration store. Always present data clearly and factually.

                ### AVAILABLE TOOLS
                1. `lookup_inventory`:
                   - Retrieves current stock levels (on-hand, reserved, available ATP) for a specific SKU and warehouse.
                   - Parameters: `sku` (e.g. SKU-ELEC-1001), `warehouseId` (e.g. WH-CENTRAL-01).
                   - If SKU or warehouse ID is missing, ask the user or coordinator for the missing information.
                2. `summarize_stock`:
                   - Summarizes stock balances across warehouses and product categories.
                   - Optional parameters: `warehouseId`, `category`.
                   - Identifies HEALTHY, LOW_STOCK, and CRITICAL_STOCK levels based on safety thresholds.

                ### OPERATIONAL RULES
                - TOOL GROUNDING: Always invoke tools for inventory facts. Never hallucinate, guess, or invent stock balances.
                - ERROR HANDLING: If a SKU or warehouse is not found, report the error status factually and suggest valid alternatives.
                - CONCISE OUTPUT: Provide clear, structured tables or bullet points with exact counts.
                """;
    }
}

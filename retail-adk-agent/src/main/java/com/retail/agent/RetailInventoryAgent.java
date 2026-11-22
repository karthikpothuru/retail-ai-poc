package com.retail.agent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.retail.agent.observability.ToolObservabilityCallbacks;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import com.retail.agent.store.RetailInventoryStore;
import com.retail.agent.tools.DiscrepancyInvestigationTool;
import com.retail.agent.tools.InventoryLookupTool;
import com.retail.agent.tools.ReplenishmentCalculatorTool;
import com.retail.agent.tools.StockSummaryTool;

import java.util.List;
import java.util.Objects;

/**
 * Enterprise Retail Inventory Investigation Agent implemented using Google ADK for Java.
 * <p>
 * Connects four specialized Google ADK FunctionTools to a shared {@link RetailInventoryStore}
 * and configures enterprise guardrails for deterministic inventory calculations and evidence-based analysis.
 */
public class RetailInventoryAgent {

    public static final String AGENT_NAME = "retail_inventory_agent";
    public static final String AGENT_DESCRIPTION =
            "An enterprise AI assistant for retail inventory operations, discrepancy investigation, and replenishment analysis";
    public static final String MODEL_NAME = "gemini-3.8-flash";

    /**
     * Single shared store instance utilized by all tools across the application runtime.
     */
    public static final RetailInventoryStore SHARED_STORE = InMemoryRetailInventoryStore.createWithDefaultData();

    /**
     * Public root agent singleton bound to the shared store instance.
     */
    public static final BaseAgent ROOT_AGENT = createAgent(SHARED_STORE);

    /**
     * Factory method creating a RetailInventoryAgent bound to a specific {@link RetailInventoryStore}.
     * Ensures all four ADK tools share the exact same store instance and integrates
     * structured observability callbacks.
     *
     * @param store the shared retail inventory store instance
     * @return a configured {@link BaseAgent} instance
     */
    public static BaseAgent createAgent(RetailInventoryStore store) {
        Objects.requireNonNull(store, "RetailInventoryStore cannot be null");

        InventoryLookupTool inventoryLookupTool = new InventoryLookupTool(store);
        DiscrepancyInvestigationTool discrepancyTool = new DiscrepancyInvestigationTool(store);
        ReplenishmentCalculatorTool replenishmentTool = new ReplenishmentCalculatorTool(store);
        StockSummaryTool stockSummaryTool = new StockSummaryTool(store);

        return LlmAgent.builder()
                .name(AGENT_NAME)
                .description(AGENT_DESCRIPTION)
                .model(MODEL_NAME)
                .instruction(buildSystemInstruction())
                .tools(List.of(
                        inventoryLookupTool.asFunctionTool(),
                        discrepancyTool.asFunctionTool(),
                        replenishmentTool.asFunctionTool(),
                        stockSummaryTool.asFunctionTool()
                ))
                .beforeToolCallbackSync(ToolObservabilityCallbacks.beforeTool())
                .afterToolCallbackSync(ToolObservabilityCallbacks.afterTool())
                .onToolErrorCallbackSync(ToolObservabilityCallbacks.onToolError())
                .build();
    }

    private static String buildSystemInstruction() {
        return """
                You are an Enterprise Retail Inventory Investigation AI Agent powered by Google ADK.
                Your purpose is to provide factual, accurate, and actionable retail inventory intelligence.

                ### DEMONSTRATION ENVIRONMENT CONTEXT
                All inventory data accessible through your tools represents an in-memory enterprise demonstration dataset.
                Clearly identify this context whenever presenting inventory findings to users.

                ### AVAILABLE TOOLS & RESPONSIBILITIES
                1. `lookup_inventory`:
                   - Use to retrieve real-time stock balances (on-hand, reserved, and available-to-promise ATP quantities).
                   - Requires both SKU code (e.g. SKU-ELEC-1001) and warehouse ID (e.g. WH-CENTRAL-01).
                2. `investigate_discrepancy`:
                   - Use to retrieve historical physical cycle count audits and calculate inventory variance.
                   - Factually categorizes audits as SHRINKAGE (unaccounted loss), SURPLUS (found overstock), or MATCH.
                   - Report documented evidence, counts, timestamps, and auditor notes exactly as recorded.
                   - DO NOT speculate or assume root causes (e.g., theft, leakage) unless explicitly stated in the documented audit notes.
                3. `calculate_replenishment`:
                   - Use to evaluate Reorder Points (ROP), Safety Stock buffers, and supplier lead times to calculate deterministic restocking proposals.
                   - Clearly state that recommendations are advisory proposals.
                   - NEVER claim or suggest that a purchase order has been placed, confirmed, or issued.
                4. `summarize_stock`:
                   - Use to aggregate stock positions across warehouses and categories.
                   - Identifies HEALTHY, LOW_STOCK, and CRITICAL_STOCK (safety buffer breached) inventory conditions.

                ### OPERATIONAL GUARDRAILS
                - TOOL-FIRST PRINCIPLE: Always invoke the appropriate tool to obtain factual stock levels, variances, or replenishment calculations.
                - NEVER INVENT DATA: Never guess, hallucinate, or fabricate stock figures, SKUs, or warehouses.
                - REQUEST MISSING INFORMATION: If a query requires a specific SKU or warehouse ID that the user did not provide, politely prompt the user to provide the missing parameter(s) before proceeding.
                - FACTUAL EVIDENCE VS ASSUMPTIONS: Clearly distinguish verified system facts from assumptions.
                - ERROR HANDLING: If a tool returns an error status (e.g., SKU_NOT_FOUND, WAREHOUSE_NOT_FOUND, NO_DATA, INVALID_INPUT), handle it gracefully, explain clearly what was not found, and suggest valid alternatives if available.
                - ADVISORY ONLY: You have read-only advisory access. You cannot modify stock levels or place purchase orders.

                Present all information professionally, concisely, and structured with clear headings or tables.
                """;
    }
}

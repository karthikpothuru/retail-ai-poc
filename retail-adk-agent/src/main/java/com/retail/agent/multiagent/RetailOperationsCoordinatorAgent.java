package com.retail.agent.multiagent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.retail.agent.RetailInventoryAgent;
import com.retail.agent.observability.ToolObservabilityCallbacks;
import com.retail.agent.store.RetailInventoryStore;

import java.util.List;
import java.util.Objects;

/**
 * Root Multi-Agent Coordinator for retail operations in Google ADK for Java.
 * <p>
 * Evaluates inbound user requests and orchestrates task delegation across three
 * dedicated specialist agents:
 * <ul>
 *   <li>{@link InventorySpecialistAgent}: Stock balances, ATP lookups, and warehouse summaries</li>
 *   <li>{@link DiscrepancySpecialistAgent}: Historical cycle count audits and variance investigations</li>
 *   <li>{@link ReplenishmentSpecialistAgent}: Reorder point calculations and advisory restocking proposals</li>
 * </ul>
 * Grounded in a shared {@link RetailInventoryStore} instance to guarantee strict data consistency.
 */
public class RetailOperationsCoordinatorAgent {

    public static final String AGENT_NAME = "retail_operations_coordinator";
    public static final String AGENT_DESCRIPTION =
            "Central coordinator AI agent for enterprise retail inventory operations, routing user queries to specialized sub-agents";
    public static final String MODEL_NAME = "gemini-3.8-flash";

    /**
     * Public root coordinator agent singleton bound to the shared store instance.
     */
    public static final BaseAgent ROOT_AGENT = createCoordinator(RetailInventoryAgent.SHARED_STORE);

    /**
     * Factory method creating a RetailOperationsCoordinatorAgent with all specialist sub-agents
     * bound to the identical shared {@link RetailInventoryStore} instance.
     *
     * @param store the shared retail inventory store instance
     * @return a configured {@link BaseAgent} coordinator instance
     */
    public static BaseAgent createCoordinator(RetailInventoryStore store) {
        Objects.requireNonNull(store, "RetailInventoryStore cannot be null");

        BaseAgent inventorySpecialist = InventorySpecialistAgent.create(store);
        BaseAgent discrepancySpecialist = DiscrepancySpecialistAgent.create(store);
        BaseAgent replenishmentSpecialist = ReplenishmentSpecialistAgent.create(store);

        return LlmAgent.builder()
                .name(AGENT_NAME)
                .description(AGENT_DESCRIPTION)
                .model(MODEL_NAME)
                .instruction(buildSystemInstruction())
                .subAgents(List.of(
                        inventorySpecialist,
                        discrepancySpecialist,
                        replenishmentSpecialist
                ))
                .beforeAgentCallbackSync(MultiAgentObservabilityCallbacks.beforeAgent())
                .afterAgentCallbackSync(MultiAgentObservabilityCallbacks.afterAgent())
                .beforeToolCallbackSync(ToolObservabilityCallbacks.beforeTool())
                .afterToolCallbackSync(ToolObservabilityCallbacks.afterTool())
                .onToolErrorCallbackSync(ToolObservabilityCallbacks.onToolError())
                .build();
    }

    private static String buildSystemInstruction() {
        return """
                You are the Retail Operations Coordinator AI Agent in an enterprise retail operations ecosystem powered by Google ADK.
                Your primary responsibility is to understand user requests, route them to the appropriate specialist agent,
                and synthesize their findings into clear, grounded, and actionable responses.

                ### DEMONSTRATION ENVIRONMENT CONTEXT
                All inventory data accessible through the specialist agents is maintained in an in-memory enterprise demonstration store.
                Clearly identify this demonstration context when presenting inventory findings.

                ### SPECIALIST AGENTS & DELEGATION RULES
                You have three specialist sub-agents available via the `transfer_to_agent` tool:

                1. `inventory_specialist`:
                   - Transfer here for:
                     * Real-time stock lookups (on-hand, reserved, available-to-promise ATP balances).
                     * Warehouse stock summaries across warehouses (e.g. WH-CENTRAL-01, WH-EAST-02, WH-WEST-03) or categories.
                     * Identifying low stock and critical stock conditions.

                2. `discrepancy_specialist`:
                   - Transfer here for:
                     * Cycle count audit histories and inventory variance calculations.
                     * Investigating stock discrepancies, shrinkage (missing inventory), or surplus (found stock).
                     * Note: The specialist reports recorded audit evidence only and never speculates on root causes.

                3. `replenishment_specialist`:
                   - Transfer here for:
                     * Reorder Point (ROP) and safety stock evaluations.
                     * Restocking calculations, supplier lead times, and replenishment quantity proposals.
                     * Note: Restocking recommendations are strictly advisory; purchase orders are never automatically created.

                ### MULTI-STEP & COMPOUND QUERIES
                - If a user asks a compound question (e.g., "Check stock for SKU-ELEC-1001 in WH-CENTRAL-01 and also check if we need to reorder"):
                  * Delegate each portion to the relevant specialist agent sequentially.
                  * Synthesize the results into a unified, coherent response.
                - Each specialist returns using transfer_to_agent after tool execution. Read its actual
                  tool responses in the shared conversation history before deciding the next step.
                - Track the portions of the latest user request already completed. Never redelegate a
                  completed portion just because the specialist transferred back. Delegate only remaining work.
                - Once all requested portions have results, answer directly without further transfers.
                  Include inventory balances, recorded discrepancy evidence, and advisory replenishment
                  findings whenever requested. Clearly report missing data or failed portions.
                - Preserve SKU and warehouse context across user turns; resolve references from history
                  when unambiguous. Refresh facts with the relevant specialist for a new user request.

                ### OPERATIONAL GUARDRAILS
                - DELEGATION FIRST: Do not guess or fabricate inventory quantities, counts, or audit details yourself. Always delegate domain queries to the appropriate specialist.
                - CONVERSATIONAL GREETINGS: For general greetings, clarifications, or questions about capabilities, respond politely and explain how your specialist agents can assist.
                - PRESERVE AUDIT TRAIL: When presenting specialist results, accurately report the numbers, status classifications, and dates provided by the specialist.
                - NEVER FABRICATE: If a specialist reports an item was not found, relay that clearly to the user without fabricating fallback data.
                - NEVER CLAIM PO CREATION: Do not state or imply that purchase orders or stock transfers have been executed.
                """;
    }
}

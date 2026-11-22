package com.retail.agent.multiagent;

/** Shared handoff contract; domain results remain in the ADK session history. */
final class DelegationInstructions {
    private DelegationInstructions() {}

    static final String RETURN_TO_COORDINATOR = """

            ### RETURN TO COORDINATOR
            - Work only on your portion of the latest user request. Use the existing session history
              to resolve references to a previously identified SKU and warehouse; ask if ambiguous.
            - After your required tool calls complete, including a not-found or error result, call
              transfer_to_agent with agent_name='retail_operations_coordinator'. The coordinator
              can read your tool results in the shared conversation history. Do not invent a result.
            - Emit only the transfer function call when handing back control, without accompanying text.
            - For requests outside your responsibility, transfer to the coordinator immediately.
            - If required identifiers cannot be resolved, ask for clarification instead of calling tools.
            - Do not transfer directly to another specialist. The coordinator owns remaining tasks
              and the complete final answer.
            """;
}

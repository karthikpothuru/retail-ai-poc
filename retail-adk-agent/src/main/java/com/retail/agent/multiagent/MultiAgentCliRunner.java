package com.retail.agent.multiagent;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import com.retail.agent.observability.AgentAuditLogger;
import io.reactivex.rxjava3.core.Flowable;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interactive Command-Line Interface Runner for the Multi-Agent Retail Operations Orchestrator.
 * <p>
 * Demonstrates hierarchical multi-agent coordination between the root coordinator
 * and three specialized sub-agents:
 * <ul>
 *   <li>{@code retail_operations_coordinator} (Root)</li>
 *   <li>{@code inventory_specialist} (Inventory balances & summaries)</li>
 *   <li>{@code discrepancy_specialist} (Cycle count audits & variance)</li>
 *   <li>{@code replenishment_specialist} (ROP calculations & advisory proposals)</li>
 * </ul>
 */
public class MultiAgentCliRunner {

    public static void main(String[] args) {

        // Ensure UTF-8 output stream compatibility for Windows PowerShell & Command Prompt
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        RunConfig runConfig = RunConfig.builder().build();

        InMemoryRunner runner =
                new InMemoryRunner(RetailOperationsCoordinatorAgent.ROOT_AGENT);

        Session session = runner
                .sessionService()
                .createSession(runner.appName(), "retail-multiagent-user")
                .blockingGet();

        AtomicInteger turnCounter = new AtomicInteger(1);

        System.out.println("================================================================================");
        System.out.println("  RETAIL OPERATIONS MULTI-AGENT ORCHESTRATOR (Google ADK Java 1.13.0)");
        System.out.println("  Architecture  : Coordinator + 3 Specialist Sub-Agents");
        System.out.println("  Coordinator   : retail_operations_coordinator");
        System.out.println("  Specialists   : inventory_specialist, discrepancy_specialist, replenishment_specialist");
        System.out.println("  Model         : gemini-3.8-flash");
        System.out.println("  Session ID    : " + session.id());
        System.out.println("  User ID       : " + session.userId());
        System.out.println("  Environment   : Shared In-Memory Enterprise Dataset (Demonstration)");
        System.out.println("  Status        : Ready (Type 'quit' or 'exit' to terminate)");
        System.out.println("================================================================================");

        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {

            while (true) {

                int turn = turnCounter.get();
                System.out.printf("%n[Turn %d | YOU] > ", turn);

                if (!scanner.hasNextLine()) {
                    break;
                }

                String userInput = scanner.nextLine();

                if (userInput == null || userInput.isBlank()) {
                    continue;
                }

                String trimmed = userInput.trim();
                if ("quit".equalsIgnoreCase(trimmed) || "exit".equalsIgnoreCase(trimmed)) {
                    System.out.println("\n[SYSTEM] Terminating Retail Multi-Agent session. Goodbye!");
                    break;
                }

                turnCounter.incrementAndGet();

                Content userMessage =
                        Content.fromParts(Part.fromText(trimmed));

                Flowable<Event> events = runner.runAsync(
                        session.userId(),
                        session.id(),
                        userMessage,
                        runConfig
                );

                events.blockingForEach(event -> {
                    event.actions().transferToAgent().ifPresent(target ->
                            System.out.printf("%n[TRANSFER EVENT] Session=%s Author=%s Target=%s",
                                    session.id(), event.author(), target));
                    // Check for agent delegation and tool calls
                    for (FunctionCall call : event.functionCalls()) {
                        String toolName = call.name().orElse("unknown_tool");
                        Map<String, Object> arguments = call.args().orElse(Map.of());

                        if ("transfer_to_agent".equals(toolName)) {
                            Object targetAgent = arguments.getOrDefault("agent_name", "unknown_agent");
                            System.out.printf("%n[AGENT DELEGATION] -> [%s] transferring task to [%s]", event.author(), targetAgent);
                        } else {
                            System.out.printf("%n[SPECIALIST TOOL]  -> Author=%s Invoking %s%s",
                                    event.author(), toolName, AgentAuditLogger.sanitizeMap(arguments));
                        }
                    }

                    // Check for tool responses
                    for (FunctionResponse resp : event.functionResponses()) {
                        String toolName = resp.name().orElse("unknown_tool");
                        if ("transfer_to_agent".equals(toolName)) {
                            System.out.printf("%n[AGENT DELEGATION] <- Transfer acknowledged");
                        } else {
                            System.out.printf("%n[SPECIALIST TOOL]  <- Author=%s %s Result=%s",
                                    event.author(), toolName,
                                    AgentAuditLogger.sanitizeMap(resp.response().orElse(Map.of())));
                        }
                    }

                    // Final synthesized agent response
                    if (event.finalResponse()) {
                        System.out.println("\n");
                        System.out.println("[" + event.author() + "] > " + event.stringifyContent());
                    }
                });
            }
        }
    }
}

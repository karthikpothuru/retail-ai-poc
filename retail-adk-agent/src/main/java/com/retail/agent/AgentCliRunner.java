package com.retail.agent;

import com.google.adk.agents.RunConfig;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced interactive CLI Runner for the Retail Inventory ADK Agent.
 * <p>
 * Optimized for Windows PowerShell and standard terminal compatibility with
 * clean ASCII framing, UTF-8 output streams, and real-time ADK tool observability.
 */
public class AgentCliRunner {

    public static void main(String[] args) {
        if (args != null && args.length > 0 && "--multi-agent".equalsIgnoreCase(args[0])) {
            com.retail.agent.multiagent.MultiAgentCliRunner.main(args);
            return;
        }

        // Ensure UTF-8 output stream compatibility for Windows PowerShell & Command Prompt
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        RunConfig runConfig = RunConfig.builder().build();

        InMemoryRunner runner =
                new InMemoryRunner(RetailInventoryAgent.ROOT_AGENT);

        Session session = runner
                .sessionService()
                .createSession(runner.appName(), "retail-user")
                .blockingGet();

        AtomicInteger turnCounter = new AtomicInteger(1);

        System.out.println("=================================================================");
        System.out.println("  RETAIL INVENTORY INVESTIGATION AI AGENT (Google ADK Java 1.13.0)");
        System.out.println("  Model         : gemini-3.8-flash");
        System.out.println("  Session ID    : " + session.id());
        System.out.println("  User ID       : " + session.userId());
        System.out.println("  Environment   : In-Memory Enterprise Dataset (Demonstration)");
        System.out.println("  Status        : Ready (Type 'quit' or 'exit' to terminate)");
        System.out.println("=================================================================");

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
                    System.out.println("\n[SYSTEM] Terminating Retail Inventory Agent session. Goodbye!");
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
                    // Real-time tool invocation observation
                    for (FunctionCall call : event.functionCalls()) {
                        String toolName = call.name().orElse("unknown_tool");
                        Map<String, Object> arguments = call.args().orElse(Map.of());
                        System.out.printf("%n[ADK TOOL INVOCATION] -> %s%s", toolName, arguments);
                    }

                    // Real-time tool execution response observation
                    for (FunctionResponse resp : event.functionResponses()) {
                        String toolName = resp.name().orElse("unknown_tool");
                        System.out.printf("%n[ADK TOOL EXECUTION]  <- %s completed", toolName);
                    }

                    // Final grounded agent response
                    if (event.finalResponse()) {
                        System.out.println("\n");
                        System.out.println("[AGENT] > " + event.stringifyContent());
                    }
                });
            }
        }
    }
}

package com.retail.agent.workflow;

import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.retail.agent.RetailInventoryAgent;
import com.retail.agent.observability.AgentAuditLogger;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/** Independent Phase 5B.1 text CLI. Phase 5A and the single-agent entry points are unchanged. */
public final class SequentialWorkflowCliRunner {
    private SequentialWorkflowCliRunner() {}

    public static void main(String[] args) {
        var runner = new InMemoryRunner(RetailInvestigationWorkflow.create(RetailInventoryAgent.SHARED_STORE));
        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            run(scanner, new PrintStream(System.out, true, StandardCharsets.UTF_8), runner);
        } finally {
            try {
                runner.close().blockingAwait();
            } finally {
                // This is the standalone process entry point, not the reusable workflow factory.
                io.reactivex.rxjava3.schedulers.Schedulers.shutdown();
            }
        }
    }

    static void run(Scanner scanner, PrintStream output, InMemoryRunner runner) {
        var session = runner.sessionService().createSession(runner.appName(), "retail-workflow-user").blockingGet();
        output.println("Retail sequential investigation | " + RetailInvestigationWorkflow.MODEL_NAME);
        output.println("Inventory -> Discrepancy -> Replenishment -> Consolidated report");
        output.println("Enter a request containing exactly one SKU and warehouse ID, or quit.");
        output.println("Example: Investigate SKU-ELEC-1001 at WH-CENTRAL-01");
        while (true) {
            output.print("\nInvestigation > ");
            if (!scanner.hasNextLine()) {
                return;
            }
            String prompt = scanner.nextLine().trim();
            if (prompt.equalsIgnoreCase("quit") || prompt.equalsIgnoreCase("exit")) {
                return;
            }
            if (prompt.isBlank()) {
                continue;
            }
            try {
                runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText(prompt)),
                                RunConfig.builder().maxLlmCalls(6).build())
                        .timeout(90, TimeUnit.SECONDS).blockingForEach(event -> {
                            event.functionCalls().forEach(call -> output.println("[TOOL CALL] " + event.author()
                                    + " " + call.name().orElse("unknown") + " "
                                    + AgentAuditLogger.sanitizeMap(call.args().orElse(Map.of()))));
                            event.functionResponses().forEach(response -> output.println("[TOOL RESULT] "
                                    + response.name().orElse("unknown") + " status="
                                    + response.response().orElse(Map.of()).getOrDefault("status", "UNKNOWN")));
                            if (event.finalResponse() && (event.author().equals(InvestigationReportAgent.NAME)
                                    || event.author().equals(RetailInvestigationWorkflow.AGENT_NAME))) {
                                output.println(event.stringifyContent());
                            }
                        });
            } catch (RuntimeException failure) {
                // SDK exceptions can include transport details; do not echo credentials or partial model text.
                output.println("Investigation failed; no consolidated report was produced. "
                        + "Check the configured Gemini access and audit log, then retry with both identifiers.");
            }
        }
    }
}

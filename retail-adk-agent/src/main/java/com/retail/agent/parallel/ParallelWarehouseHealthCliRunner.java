package com.retail.agent.parallel;

import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.retail.agent.RetailInventoryAgent;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/** Separate Phase 5B.2 CLI; no changes to existing entry points or credentials. */
public final class ParallelWarehouseHealthCliRunner {
    private ParallelWarehouseHealthCliRunner() {}

    public static void main(String[] args) {
        var runner = new InMemoryRunner(WarehouseHealthWorkflow.create(RetailInventoryAgent.SHARED_STORE));
        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            var session = runner.sessionService().createSession(runner.appName(), "warehouse-health-user").blockingGet();
            System.out.println("Parallel warehouse health | " + WarehouseHealthWorkflow.MODEL_NAME);
            System.out.println("Enter 'assess warehouses' for all three warehouses, or 'quit'.");
            while (scanner.hasNextLine()) {
                String request = scanner.nextLine().trim();
                if (request.equalsIgnoreCase("quit") || request.equalsIgnoreCase("exit")) {
                    return;
                }
                if (request.isBlank()) {
                    continue;
                }
                try {
                    runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText(request)),
                                    RunConfig.builder().maxLlmCalls(2).build())
                            .blockingForEach(event -> {
                                if (event.finalResponse() && event.author().equals(WarehouseHealthWorkflow.NAME)) {
                                    System.out.println(event.stringifyContent());
                                }
                            });
                } catch (RuntimeException failure) {
                    System.out.println("Workflow infrastructure failed; no consolidated report produced. Consult workflow audit logs.");
                }
            }
        } finally {
            try {
                runner.close().blockingAwait();
            } finally {
                Schedulers.shutdown();
            }
        }
    }
}

package com.retail.agent.parallel;

import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.retail.agent.RetailInventoryAgent;
import com.retail.agent.observability.AgentAuditLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "runLiveParallelWarehouseTest", matches = "true")
class ParallelWarehouseHealthLiveSmokeTest {
    @Test
    void geminiExecutesThreeAssignedWarehouseToolsAndAggregates() {
        AgentAuditLogger.clearAuditHistory();
        var runner = new InMemoryRunner(WarehouseHealthWorkflow.create(RetailInventoryAgent.SHARED_STORE));
        try {
            var session = runner.sessionService().createSession(runner.appName(), "live-health").blockingGet();
            var events = runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText("assess warehouses")),
                    RunConfig.builder().build()).toList().blockingGet();
            assertThat(WarehouseHealthWorkflowTest.aggregate(events)).containsEntry("status", "FULL_SUCCESS")
                    .containsEntry("successfulBranches", 3);
            for (String warehouse : WarehouseAssessmentResults.WAREHOUSES) {
                assertThat(AgentAuditLogger.getAuditHistory()).anySatisfy(entry -> {
                    assertThat(entry.outcome()).isEqualTo("START");
                    assertThat(entry.toolName()).isEqualTo("summarize_stock");
                    assertThat(entry.args()).containsEntry("warehouseId", warehouse);
                });
            }
        } finally { runner.close().blockingAwait(); }
    }
}

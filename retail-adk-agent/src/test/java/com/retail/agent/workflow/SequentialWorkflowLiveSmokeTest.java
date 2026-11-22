package com.retail.agent.workflow;

import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.retail.agent.RetailInventoryAgent;
import com.retail.agent.tools.InventoryLookupTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit opt-in only: mvn test -Dtest=SequentialWorkflowLiveSmokeTest -DrunLiveSequentialWorkflowTest=true */
@EnabledIfSystemProperty(named = "runLiveSequentialWorkflowTest", matches = "true")
class SequentialWorkflowLiveSmokeTest {
    @Test
    void geminiExecutesAllStagesAndReturnsGroundedReport() {
        var store = RetailInventoryAgent.SHARED_STORE;
        var runner = new InMemoryRunner(RetailInvestigationWorkflow.create(store));
        var session = runner.sessionService().createSession(runner.appName(), "live-sequential").blockingGet();
        var events = runner.runAsync(session.userId(), session.id(), Content.fromParts(Part.fromText(
                        "Investigate SKU-ELEC-1001 at WH-CENTRAL-01")), RunConfig.builder().maxLlmCalls(6).build())
                .timeout(90, TimeUnit.SECONDS).toList().blockingGet();
        assertThat(events.stream().flatMap(event -> event.functionCalls().stream())
                .map(call -> call.name().orElseThrow()).toList())
                .containsExactly("lookup_inventory", "investigate_discrepancy", "calculate_replenishment");
        assertThat(events.stream().flatMap(event -> event.functionCalls().stream()).toList()).allSatisfy(call ->
                assertThat(call.args().orElseThrow()).containsEntry("sku", "SKU-ELEC-1001")
                        .containsEntry("warehouseId", "WH-CENTRAL-01"));
        assertThat(events.stream().flatMap(event -> event.functionResponses().stream())
                .map(response -> response.name().orElseThrow()).toList())
                .containsExactly("lookup_inventory", "investigate_discrepancy", "calculate_replenishment");
        var stock = new InventoryLookupTool(store).lookupInventory("SKU-ELEC-1001", "WH-CENTRAL-01");
        assertThat(events.stream().filter(event -> event.author().equals(InvestigationReportAgent.NAME))
                .map(event -> event.stringifyContent()).toList()).singleElement().satisfies(report ->
                assertThat(report).contains("onHandQuantity: " + stock.onHandQuantity(),
                        "availableQuantity: " + stock.availableQuantity(), "Discrepancy investigation", "Replenishment assessment"));
    }
}

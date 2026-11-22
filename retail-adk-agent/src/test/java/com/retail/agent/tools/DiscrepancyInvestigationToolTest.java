package com.retail.agent.tools;

import com.google.adk.tools.FunctionTool;
import com.google.genai.types.FunctionDeclaration;
import com.retail.agent.store.InMemoryRetailInventoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DiscrepancyInvestigationTool Tests")
class DiscrepancyInvestigationToolTest {

    private InMemoryRetailInventoryStore store;
    private DiscrepancyInvestigationTool tool;

    @BeforeEach
    void setUp() {
        store = InMemoryRetailInventoryStore.createWithDefaultData();
        tool = new DiscrepancyInvestigationTool(store);
    }

    @Test
    @DisplayName("Should retrieve matching audit record with zero variance")
    void shouldInvestigateMatchingCycleCount() {
        // SKU-ELEC-1001 @ Central: physical 140, system 140, variance 0
        DiscrepancyInvestigationTool.DiscrepancyInvestigationResult result =
                tool.investigateDiscrepancy("SKU-ELEC-1001", "WH-CENTRAL-01");

        assertThat(result.status()).isEqualTo("FOUND");
        assertThat(result.totalAuditCount()).isEqualTo(1);
        assertThat(result.matchingAuditCount()).isEqualTo(1);
        assertThat(result.shrinkageAuditCount()).isZero();
        assertThat(result.surplusAuditCount()).isZero();
        assertThat(result.netVarianceUnits()).isZero();
        assertThat(result.auditRecords().get(0).classification()).isEqualTo("MATCH");
        assertThat(result.auditRecords().get(0).documentedNotes()).contains("Pallet seal 992-A intact");
    }

    @Test
    @DisplayName("Should accurately calculate and classify inventory shrinkage")
    void shouldInvestigateShrinkageDiscrepancy() {
        // SKU-ELEC-1002 @ East: physical 95, system 102, variance -7
        DiscrepancyInvestigationTool.DiscrepancyInvestigationResult result =
                tool.investigateDiscrepancy("SKU-ELEC-1002", "WH-EAST-02");

        assertThat(result.status()).isEqualTo("FOUND");
        assertThat(result.totalAuditCount()).isEqualTo(1);
        assertThat(result.shrinkageAuditCount()).isEqualTo(1);
        assertThat(result.netVarianceUnits()).isEqualTo(-7);

        DiscrepancyInvestigationTool.AuditEvidence evidence = result.auditRecords().get(0);
        assertThat(evidence.classification()).isEqualTo("SHRINKAGE");
        assertThat(evidence.variance()).isEqualTo(-7);
        assertThat(evidence.physicalCount()).isEqualTo(95);
        assertThat(evidence.systemExpectedQuantity()).isEqualTo(102);
        assertThat(evidence.documentedNotes()).contains("Master carton was opened");
    }

    @Test
    @DisplayName("Should accurately calculate and classify inventory surplus")
    void shouldInvestigateSurplusDiscrepancy() {
        // SKU-ELEC-1003 @ East: physical 420, system 415, variance +5
        DiscrepancyInvestigationTool.DiscrepancyInvestigationResult result =
                tool.investigateDiscrepancy("SKU-ELEC-1003", "WH-EAST-02");

        assertThat(result.status()).isEqualTo("FOUND");
        assertThat(result.surplusAuditCount()).isEqualTo(1);
        assertThat(result.netVarianceUnits()).isEqualTo(5);

        DiscrepancyInvestigationTool.AuditEvidence evidence = result.auditRecords().get(0);
        assertThat(evidence.classification()).isEqualTo("SURPLUS");
        assertThat(evidence.variance()).isEqualTo(5);
        assertThat(evidence.documentedNotes()).contains("Unscanned delivery box");
    }

    @Test
    @DisplayName("Should return NO_RECORDS_FOUND when no cycle counts exist for SKU/warehouse")
    void shouldHandleNoAuditRecords() {
        // SKU-ELEC-1003 @ Central has no cycle counts in default seed
        DiscrepancyInvestigationTool.DiscrepancyInvestigationResult result =
                tool.investigateDiscrepancy("SKU-ELEC-1003", "WH-CENTRAL-01");

        assertThat(result.status()).isEqualTo("NO_RECORDS_FOUND");
        assertThat(result.totalAuditCount()).isZero();
        assertThat(result.auditRecords()).isEmpty();
    }

    @Test
    @DisplayName("Should return SKU_NOT_FOUND when querying unknown SKU")
    void shouldHandleUnknownSku() {
        DiscrepancyInvestigationTool.DiscrepancyInvestigationResult result =
                tool.investigateDiscrepancy("SKU-UNKNOWN-99", "WH-CENTRAL-01");

        assertThat(result.status()).isEqualTo("SKU_NOT_FOUND");
    }

    @Test
    @DisplayName("Should return WAREHOUSE_NOT_FOUND when querying unknown warehouse")
    void shouldHandleUnknownWarehouse() {
        DiscrepancyInvestigationTool.DiscrepancyInvestigationResult result =
                tool.investigateDiscrepancy("SKU-ELEC-1001", "WH-UNKNOWN");

        assertThat(result.status()).isEqualTo("WAREHOUSE_NOT_FOUND");
    }

    @Test
    @DisplayName("Should return INVALID_INPUT for blank or null inputs")
    void shouldRejectInvalidInputs() {
        assertThat(tool.investigateDiscrepancy(null, "WH-01").status()).isEqualTo("INVALID_INPUT");
        assertThat(tool.investigateDiscrepancy("SKU-1", null).status()).isEqualTo("INVALID_INPUT");
        assertThat(tool.investigateDiscrepancy("", "  ").status()).isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("Should expose valid Google ADK FunctionTool and execute via runAsync")
    void shouldExecuteViaAdkFunctionTool() {
        FunctionTool functionTool = tool.asFunctionTool();
        assertThat(functionTool.name()).isEqualTo("investigate_discrepancy");

        FunctionDeclaration decl = functionTool.declaration().orElseThrow();
        assertThat(decl.name()).contains("investigate_discrepancy");

        Map<String, Object> args = Map.of(
                "sku", "SKU-ELEC-1002",
                "warehouseId", "WH-EAST-02"
        );

        Map<String, Object> response = functionTool.runAsync(args, null).blockingGet();
        assertThat(response).isNotNull();
        assertThat(response.get("status")).isEqualTo("FOUND");
        assertThat(response.get("netVarianceUnits")).isEqualTo(-7);
    }
}

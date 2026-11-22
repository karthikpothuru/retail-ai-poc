package com.retail.agent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CycleCountRecord Domain Entity Tests")
class CycleCountRecordTest {

    @Test
    @DisplayName("Should detect match when physical equals system quantity")
    void shouldIdentifyExactMatch() {
        CycleCountRecord record = new CycleCountRecord(
                "CC-001",
                "SKU-101",
                "WH-01",
                100,
                100,
                Instant.now(),
                "AUD-1",
                "Count verified"
        );

        assertThat(record.variance()).isEqualTo(0);
        assertThat(record.hasDiscrepancy()).isFalse();
        assertThat(record.discrepancyType()).isEqualTo(CycleCountRecord.DiscrepancyType.MATCH);
    }

    @Test
    @DisplayName("Should detect shrinkage when physical is less than system quantity")
    void shouldIdentifyShrinkage() {
        CycleCountRecord record = new CycleCountRecord(
                "CC-002",
                "SKU-102",
                "WH-02",
                95,
                100,
                Instant.now(),
                "AUD-1",
                "Missing 5 units"
        );

        assertThat(record.variance()).isEqualTo(-5);
        assertThat(record.hasDiscrepancy()).isTrue();
        assertThat(record.discrepancyType()).isEqualTo(CycleCountRecord.DiscrepancyType.SHRINKAGE);
    }

    @Test
    @DisplayName("Should detect surplus when physical exceeds system quantity")
    void shouldIdentifySurplus() {
        CycleCountRecord record = new CycleCountRecord(
                "CC-003",
                "SKU-103",
                "WH-03",
                105,
                100,
                Instant.now(),
                "AUD-1",
                "Found extra 5 units"
        );

        assertThat(record.variance()).isEqualTo(5);
        assertThat(record.hasDiscrepancy()).isTrue();
        assertThat(record.discrepancyType()).isEqualTo(CycleCountRecord.DiscrepancyType.SURPLUS);
    }

    @Test
    @DisplayName("Should reject negative count numbers")
    void shouldRejectNegativeCounts() {
        assertThatThrownBy(() -> new CycleCountRecord("CC-1", "SKU-1", "WH-1", -1, 10, Instant.now(), "AUD-1", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Physical count cannot be negative");

        assertThatThrownBy(() -> new CycleCountRecord("CC-1", "SKU-1", "WH-1", 10, -5, Instant.now(), "AUD-1", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("System expected quantity cannot be negative");
    }
}

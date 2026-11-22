package com.retail.agent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ReorderPolicy Domain Entity Tests")
class ReorderPolicyTest {

    @Test
    @DisplayName("Should accurately determine reorder necessity and safety stock breach")
    void shouldEvaluateReorderConditions() {
        // ROP: 50, Safety Stock: 20
        ReorderPolicy policy = new ReorderPolicy("SKU-100", "WH-01", 50, 20, 100, 7);

        // Healthy stock (60 > 50)
        assertThat(policy.isReorderNeeded(60)).isFalse();
        assertThat(policy.isSafetyStockBreached(60)).isFalse();

        // Exact ROP threshold (50 <= 50)
        assertThat(policy.isReorderNeeded(50)).isTrue();
        assertThat(policy.isSafetyStockBreached(50)).isFalse();

        // Below ROP but above safety stock (35)
        assertThat(policy.isReorderNeeded(35)).isTrue();
        assertThat(policy.isSafetyStockBreached(35)).isFalse();

        // Safety stock breached (15 < 20)
        assertThat(policy.isReorderNeeded(15)).isTrue();
        assertThat(policy.isSafetyStockBreached(15)).isTrue();
    }

    @Test
    @DisplayName("Should reject policy where safety stock exceeds reorder point")
    void shouldRejectSafetyStockGreaterThanReorderPoint() {
        assertThatThrownBy(() -> new ReorderPolicy("SKU-1", "WH-1", 20, 30, 50, 7))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Safety stock (30) cannot exceed reorder point (20)");
    }

    @Test
    @DisplayName("Should reject negative parameters or zero/negative reorder quantity")
    void shouldRejectInvalidPolicyParameters() {
        assertThatThrownBy(() -> new ReorderPolicy("SKU-1", "WH-1", -1, 0, 50, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reorder point cannot be negative");

        assertThatThrownBy(() -> new ReorderPolicy("SKU-1", "WH-1", 10, 5, 0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reorder quantity must be positive");

        assertThatThrownBy(() -> new ReorderPolicy("SKU-1", "WH-1", 10, 5, 50, -2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Lead time days cannot be negative");
    }
}

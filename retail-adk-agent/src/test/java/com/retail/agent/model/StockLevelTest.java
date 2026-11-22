package com.retail.agent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StockLevel Domain Entity Tests")
class StockLevelTest {

    @Test
    @DisplayName("Should calculate available quantity as on-hand minus reserved")
    void shouldCalculateAvailableStockCorrectly() {
        StockLevel stock = new StockLevel("SKU-ELEC-1001", "WH-CENTRAL-01", 140, 25);

        assertThat(stock.sku()).isEqualTo("SKU-ELEC-1001");
        assertThat(stock.warehouseId()).isEqualTo("WH-CENTRAL-01");
        assertThat(stock.onHandQuantity()).isEqualTo(140);
        assertThat(stock.reservedQuantity()).isEqualTo(25);
        assertThat(stock.availableQuantity()).isEqualTo(115);
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException if reserved exceeds on-hand")
    void shouldRejectReservedGreaterThanOnHand() {
        assertThatThrownBy(() -> new StockLevel("SKU-1", "WH-01", 10, 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reserved quantity (15) cannot exceed on-hand quantity (10)");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for negative stock numbers")
    void shouldRejectNegativeQuantities() {
        assertThatThrownBy(() -> new StockLevel("SKU-1", "WH-01", -1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("On-hand quantity cannot be negative");

        assertThatThrownBy(() -> new StockLevel("SKU-1", "WH-01", 10, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Reserved quantity cannot be negative");
    }

    @Test
    @DisplayName("Should produce a new immutable instance when updated")
    void shouldReturnNewInstanceWithUpdatedStock() {
        StockLevel original = new StockLevel("SKU-ELEC-1001", "WH-CENTRAL-01", 100, 20);
        StockLevel updated = original.withUpdatedStock(150, 30);

        assertThat(updated.onHandQuantity()).isEqualTo(150);
        assertThat(updated.reservedQuantity()).isEqualTo(30);
        assertThat(updated.availableQuantity()).isEqualTo(120);
        // Original remains untouched
        assertThat(original.onHandQuantity()).isEqualTo(100);
        assertThat(original.reservedQuantity()).isEqualTo(20);
    }
}

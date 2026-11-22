package com.retail.agent.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ProductSku Domain Entity Tests")
class ProductSkuTest {

    @Test
    @DisplayName("Should successfully instantiate a valid ProductSku record")
    void shouldCreateValidProductSku() {
        ProductSku sku = new ProductSku(
                "SKU-ELEC-1001",
                "OmniView 55-Inch 4K OLED Smart TV",
                "Consumer Electronics",
                new BigDecimal("799.99"),
                "012345678901",
                "EACH"
        );

        assertThat(sku.sku()).isEqualTo("SKU-ELEC-1001");
        assertThat(sku.name()).isEqualTo("OmniView 55-Inch 4K OLED Smart TV");
        assertThat(sku.category()).isEqualTo("Consumer Electronics");
        assertThat(sku.unitPrice()).isEqualTo(new BigDecimal("799.99"));
        assertThat(sku.barcode()).isEqualTo("012345678901");
        assertThat(sku.unitOfMeasure()).isEqualTo("EACH");
    }

    @Test
    @DisplayName("Should throw NullPointerException when required fields are null")
    void shouldRejectNullFields() {
        assertThatThrownBy(() -> new ProductSku(null, "Name", "Cat", BigDecimal.TEN, "UPC", "EACH"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("SKU cannot be null");

        assertThatThrownBy(() -> new ProductSku("SKU-1", null, "Cat", BigDecimal.TEN, "UPC", "EACH"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Product name cannot be null");

        assertThatThrownBy(() -> new ProductSku("SKU-1", "Name", null, BigDecimal.TEN, "UPC", "EACH"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Category cannot be null");

        assertThatThrownBy(() -> new ProductSku("SKU-1", "Name", "Cat", null, "UPC", "EACH"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Unit price cannot be null");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when SKU or Name is blank")
    void shouldRejectBlankFields() {
        assertThatThrownBy(() -> new ProductSku("   ", "Name", "Cat", BigDecimal.TEN, "UPC", "EACH"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SKU cannot be blank");

        assertThatThrownBy(() -> new ProductSku("SKU-1", "   ", "Cat", BigDecimal.TEN, "UPC", "EACH"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Product name cannot be blank");
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException for negative unit price")
    void shouldRejectNegativePrice() {
        assertThatThrownBy(() -> new ProductSku("SKU-1", "Name", "Cat", new BigDecimal("-0.01"), "UPC", "EACH"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unit price cannot be negative");
    }
}

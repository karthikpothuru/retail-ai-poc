package com.retail.inventory.service;

import com.retail.inventory.model.Inventory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetailInventoryServiceTest {

    private final RetailInventoryService service = new RetailInventoryService();

    @Test
    void returnsWirelessMouseInventory() {
        Inventory item = service.getInventory("wm-101");

        assertNotNull(item);
        assertEquals(105, item.actualInventory());
        assertEquals(30, item.reorderThreshold());
    }

    @Test
    void flagsKeyboardForReorder() {
        var status = service.getReorderStatus("KB-201");

        assertEquals(true, status.get("reorderRequired"));
    }

    @Test
    void rejectsInvalidDateRange() {
        assertThrows(IllegalArgumentException.class,
                () -> service.getSalesSummary("WM-101", "2026-10-31", "2026-10-01"));
    }
}

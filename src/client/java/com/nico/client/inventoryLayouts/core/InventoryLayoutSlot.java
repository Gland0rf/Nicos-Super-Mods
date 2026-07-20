package com.nico.client.inventoryLayouts.core;

public class InventoryLayoutSlot {
    private int inventorySlot;
    private String baseItemId;
    private String skyblockItemId;
    private String displayName;
    private int count;

    public InventoryLayoutSlot() { }

    public InventoryLayoutSlot(
            int inventorySlot,
            String baseItemId,
            String skyblockItemId,
            String displayName,
            int count
    ) {
        this.inventorySlot = inventorySlot;
        this.baseItemId = baseItemId;
        this.skyblockItemId = skyblockItemId;
        this.displayName = displayName;
        this.count = count;
    }

    public int inventorySlot() {
        return inventorySlot;
    }

    public String baseItemId() {
        return baseItemId == null ? "" : baseItemId;
    }

    public String skyblockItemId() {
        return skyblockItemId == null ? "" : skyblockItemId;
    }

    public String displayName() {
        return displayName == null || displayName.isBlank() ? baseItemId() : displayName;
    }

    public int count() {
        return Math.max(1, count);
    }

    public boolean isValid() {
        return inventorySlot >= 0 && inventorySlot < InventoryLayout.INVENTORY_SLOT_COUNT
                && !baseItemId().isBlank();
    }
}

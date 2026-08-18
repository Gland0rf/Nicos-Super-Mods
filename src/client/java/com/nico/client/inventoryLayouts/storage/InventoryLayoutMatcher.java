package com.nico.client.inventoryLayouts.storage;

import com.nico.client.configuration.category.CategoryOther;
import com.nico.client.inventoryLayouts.core.InventoryLayout;
import com.nico.client.inventoryLayouts.core.InventoryLayoutSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class InventoryLayoutMatcher {
    private InventoryLayoutMatcher() { }

    public static SlotMatchState getState(
            InventoryLayout layout,
            Player player,
            int inventorySlot,
            CategoryOther.InventoryLayouts config
    ) {
        InventoryLayoutSlot expected = layout.expectedAt(inventorySlot);
        ItemStack actual = player.getInventory().getItem(inventorySlot);

        if (expected == null) {
            return actual.isEmpty() ? SlotMatchState.CORRECT_EMPTY : SlotMatchState.UNEXPECTED_ITEM;
        }

        if (actual.isEmpty()) {
            return SlotMatchState.MISSING_ITEM;
        }

        if (SkyblockItemIdentity.matches(expected, actual, config.matchStackCounts)) {
            return SlotMatchState.CORRECT;
        }

        return SlotMatchState.WRONG_ITEM;
    }

    public static LayoutProgress getProgress(
        InventoryLayout layout,
        Player player,
        CategoryOther.InventoryLayouts config
    ) {
        int correctSlots = 0;

        for (int inventorySlot = 0; inventorySlot < InventoryLayout.INVENTORY_SLOT_COUNT; inventorySlot++) {
            SlotMatchState state = getState(layout, player, inventorySlot, config);
            if (state == SlotMatchState.CORRECT || state == SlotMatchState.CORRECT_EMPTY) {
                correctSlots++;
            }
        }

        return new LayoutProgress(correctSlots, InventoryLayout.INVENTORY_SLOT_COUNT);
    }

    public static final class LayoutProgress {
        private final int correctSlots;
        private final int totalSlots;

        public LayoutProgress(int correctSlots, int totalSlots) {
            this.correctSlots = correctSlots;
            this.totalSlots = totalSlots;
        }

        public int correctSlots() {
            return correctSlots;
        }

        public int totalSlots() {
            return totalSlots;
        }

        public boolean complete() {
            return correctSlots >= totalSlots;
        }
    }
}

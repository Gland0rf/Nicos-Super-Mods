package com.nico.client.inventoryLayouts.core;

import com.nico.client.inventoryLayouts.storage.SkyblockItemIdentity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class InventoryLayout {
    public static final int INVENTORY_SLOT_COUNT = 36;

    private String name;
    private long createdAtEpochMillis;
    private List<InventoryLayoutSlot> slots = new ArrayList<>();

    public InventoryLayout() { }

    public InventoryLayout(String name, long createdAtEpochMillis, List<InventoryLayoutSlot> slots) {
        this.name = name;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.slots = new ArrayList<>(slots);
        sanitize();
    }

    public static InventoryLayout capture(String name, Player player) {
        List<InventoryLayoutSlot> savedSlots = new ArrayList<>();

        for (int inventorySlot = 0; inventorySlot < INVENTORY_SLOT_COUNT; inventorySlot++) {
            ItemStack stack = player.getInventory().getItem(inventorySlot);
            if (stack.isEmpty()) continue;

            savedSlots.add(SkyblockItemIdentity.capture(inventorySlot, stack));
        }

        return new InventoryLayout(name, System.currentTimeMillis(), savedSlots);
    }

    public String name() {
        return name == null || name.isBlank() ? "Unnamed layout" : name;
    }

    public long createdAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public List<InventoryLayoutSlot> slots() {
        return List.copyOf(slots);
    }

    public InventoryLayoutSlot expectedAt(int inventorySlot) {
        for (InventoryLayoutSlot slot : slots) {
            if (slot.inventorySlot() == inventorySlot) {
                return slot;
            }
        }
        return null;
    }

    public int itemSlotCount() {
        return slots.size();
    }

    public void sanitize() {
        if (name == null || name.isBlank()) {
            name = "Unnamed layout";
        }

        if (createdAtEpochMillis <= 0L) {
            createdAtEpochMillis = System.currentTimeMillis();
        }

        if (slots == null) {
            slots = new ArrayList<>();
        }

        boolean[] occupied = new boolean[INVENTORY_SLOT_COUNT];
        List<InventoryLayoutSlot> sanitizedSlots = new ArrayList<>();

        for (InventoryLayoutSlot slot : slots) {
            if (slot == null || !slot.isValid() || occupied[slot.inventorySlot()]) {
                continue;
            }

            occupied[slot.inventorySlot()] = true;
            sanitizedSlots.add(slot);
        }

        slots = sanitizedSlots;
        slots.sort(Comparator.comparingInt(InventoryLayoutSlot::inventorySlot));
    }
}

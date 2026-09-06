package com.nico.client.inventoryLayouts.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.mojang.serialization.JsonOps;
import com.nico.client.inventoryLayouts.core.InventoryLayoutSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Stores a count-independent ItemStack snapshot for layout ghost rendering.
 * Matching still uses SkyblockItemIdentity; this data is only a visual fallback.
 */
public class InventoryLayoutStackSnapshot {
    private static final Map<InventoryLayoutSlot, ItemStack> RESTORED_CACHE = new WeakHashMap<>();

    private InventoryLayoutStackSnapshot() { }

    public static JsonElement capture(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return JsonNull.INSTANCE;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return JsonNull.INSTANCE;

        try {
            ItemStack visual = stack.copy();
            visual.setCount(1);

            var ops = RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess());
            return ItemStack.CODEC
                    .encodeStart(ops, visual)
                    .result()
                    .orElse(JsonNull.INSTANCE);
        } catch (RuntimeException ignored) {
            return JsonNull.INSTANCE;
        }
    }

    public static ItemStack restore(InventoryLayoutSlot slot) {
        if (slot == null) return ItemStack.EMPTY;

        if (RESTORED_CACHE.containsKey(slot)) return RESTORED_CACHE.get(slot);

        ItemStack restored = restore(slot.visualStack());
        RESTORED_CACHE.put(slot, restored);
        return restored;
    }

    public static ItemStack restore(JsonElement encoded) {
        if (encoded == null || encoded.isJsonNull()) return ItemStack.EMPTY;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return ItemStack.EMPTY;

        try {
            var ops = RegistryOps.create(JsonOps.INSTANCE, minecraft.level.registryAccess());
            ItemStack restored = ItemStack.CODEC
                    .parse(ops, encoded)
                    .result()
                    .orElse(ItemStack.EMPTY);

            if (restored.isEmpty()) return ItemStack.EMPTY;

            ItemStack visual = restored.copy();
            visual.setCount(1);
            return visual;
        } catch (RuntimeException ignored) {
            return ItemStack.EMPTY;
        }
    }
}

package com.nico.client.inventoryLayouts.render;

import com.nico.client.configuration.category.CategoryOther;
import com.nico.client.inventoryLayouts.core.InventoryLayout;
import com.nico.client.inventoryLayouts.core.InventoryLayoutSlot;
import com.nico.client.inventoryLayouts.storage.SkyblockItemIdentity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class InventoryLayoutRenderUtil {
    private static final int GHOST_WASH = 0x780F1720;

    private InventoryLayoutRenderUtil() { }

    public static ItemStack findRepresentativeStack(
            InventoryLayoutSlot expected,
            Player player,
            CategoryOther.InventoryLayouts config
    ) {
        if (expected == null) return ItemStack.EMPTY;

        if (player != null) {
            for (int inventorySlot = 0;
                 inventorySlot < InventoryLayout.INVENTORY_SLOT_COUNT;
                 inventorySlot++) {

                ItemStack candidate = player.getInventory().getItem(inventorySlot);
                if (!SkyblockItemIdentity.matches(expected, candidate, false)) {
                    continue;
                }

                ItemStack copy = candidate.copy();
                copy.setCount(1);
                return copy;
            }
        }

        Identifier identifier = Identifier.tryParse(expected.baseItemId());
        if (identifier == null) return ItemStack.EMPTY;

        Item item = BuiltInRegistries.ITEM.getValue(identifier);
        if (item == null) return ItemStack.EMPTY;

        ItemStack fallback = new ItemStack(item);
        return fallback.isEmpty() ? ItemStack.EMPTY : fallback;
    }

    public static void renderGhostItem(GuiGraphics graphics, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) return;

        graphics.renderItem(stack, x, y);

        graphics.fill(
                x + 1,
                y + 1,
                x + 15,
                y + 15,
                GHOST_WASH
        );
    }

    public static void drawBorder(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            int color
    ) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    public static void drawMissingMarker(GuiGraphics graphics, int x, int y) {
        Minecraft minecraft = Minecraft.getInstance();
        graphics.drawCenteredString(
                minecraft.font,
                "?",
                x + 8,
                y + 4,
                0xFFFFDD55
        );
    }
}
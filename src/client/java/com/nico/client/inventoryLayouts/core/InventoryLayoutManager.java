package com.nico.client.inventoryLayouts.core;

import com.nico.client.configuration.NsmConfig;
import com.nico.client.configuration.category.CategoryOther;
import com.nico.client.inventoryLayouts.storage.InventoryLayoutMatcher;
import com.nico.client.inventoryLayouts.storage.InventoryLayoutStorage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

public class InventoryLayoutManager {
    private static final long AUTO_COMPLETE_GRACE_MS = 750L;

    private final InventoryLayoutStorage storage;

    private InventoryLayout activeLayout;
    private long activatedMillis;

    public InventoryLayoutManager(InventoryLayoutStorage storage) {
        this.storage = storage;
    }

    public InventoryLayoutStorage storage() {
        return storage;
    }

    public CategoryOther.InventoryLayouts config() {
        return NsmConfig.INSTANCE.other.inventoryLayouts;
    }

    public InventoryLayout activeLayout() {
        return activeLayout;
    }

    public boolean isActive(InventoryLayout layout) {
        return activeLayout != null
                && layout != null
                && activeLayout.name().equalsIgnoreCase(layout.name());
    }

    public void delete(InventoryLayout layout) {
        if (layout == null) return;

        if (isActive(layout)) deactivate(false);
        storage.delete(layout.name());
    }

    public void activate(InventoryLayout layout) {
        activeLayout = layout;
        activatedMillis = System.currentTimeMillis();

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && layout != null) {
            minecraft.player.displayClientMessage(
                    Component.literal("[NSM] Loaded inventory layout: ")
                            .withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(layout.name()).withStyle(ChatFormatting.WHITE)),
                    false
            );
        }
    }

    public void deactivate(boolean notifyPlayer) {
        InventoryLayout previous = activeLayout;
        activeLayout = null;
        activatedMillis = 0L;

        Minecraft minecraft = Minecraft.getInstance();
        if (notifyPlayer && previous != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.literal("[NSM] Stopped inventory layout: ")
                            .withStyle(ChatFormatting.YELLOW)
                            .append(Component.literal(previous.name()).withStyle(ChatFormatting.WHITE)),
                    false
            );
        }
    }

    public InventoryLayoutMatcher.LayoutProgress progress(Player player) {
        if (activeLayout == null || player == null) {
            return new InventoryLayoutMatcher.LayoutProgress(0, InventoryLayout.INVENTORY_SLOT_COUNT);
        }
        return InventoryLayoutMatcher.getProgress(activeLayout, player, config());
    }

    public void tick(Minecraft minecraft) {
        CategoryOther.InventoryLayouts config = config();

        if (!config.enabled || activeLayout == null || minecraft.player == null) return;

        if (!config.autoDisableWhenComplete) return;

        if (System.currentTimeMillis() - activatedMillis < AUTO_COMPLETE_GRACE_MS) return;

        InventoryLayoutMatcher.LayoutProgress progress = progress(minecraft.player);
        if (!progress.complete()) return;

        String completedName = activeLayout.name();
        activeLayout = null;
        activatedMillis = 0L;

        minecraft.player.displayClientMessage(
                Component.literal("[NSM] Inventory layout complete: ")
                        .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                        .append(Component.literal(completedName).withStyle(ChatFormatting.WHITE)),
                false
        );
    }
}

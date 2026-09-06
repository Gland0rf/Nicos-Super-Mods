package com.nico.client.inventoryLayouts.render;

import com.nico.client.hud.HudElement;
import com.nico.client.hud.HudLayoutManager;
import com.nico.client.inventoryLayouts.core.InventoryLayout;
import com.nico.client.inventoryLayouts.core.InventoryLayoutManager;
import com.nico.client.inventoryLayouts.core.InventoryLayoutSlot;
import com.nico.client.inventoryLayouts.storage.InventoryLayoutMatcher;
import com.nico.client.inventoryLayouts.storage.SlotMatchState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;


public class InventoryLayoutOverlay {
    private static final int INVENTORY_GUI_WIDTH = 176;
    private static final int INVENTORY_GUI_HEIGHT = 166;

    private static final int CORRECT_FILL = 0x4433CC66;
    private static final int CORRECT_BORDER = 0xFF55FF88;
    private static final int WRONG_FILL = 0x66FF3333;
    private static final int WRONG_BORDER = 0xFFFF5555;
    private static final int UNEXPECTED_FILL = 0x66FF55AA;
    private static final int UNEXPECTED_BORDER = 0xFFFF77BB;
    private static final int MISSING_FILL = 0x4433AAFF;
    private static final int MISSING_BORDER = 0xFF55CCFF;

    private static PendingLayoutTooltip pendingTooltip;

    private InventoryLayoutOverlay() { }

    public static void render(
            InventoryScreen screen,
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            InventoryLayoutManager manager,
            HudLayoutManager hudLayoutManager
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        InventoryLayout layout = manager.activeLayout();

        if (!manager.config().enabled || layout == null || minecraft.player == null) {
            return;
        }

        graphics.nextStratum();

        int left = (screen.width - INVENTORY_GUI_WIDTH) / 2;
        int top = (screen.height - INVENTORY_GUI_HEIGHT) / 2;

        for (Slot slot : screen.getMenu().slots) {
            int inventorySlot = getPlayerInventorySlot(screen, slot);
            if (inventorySlot < 0) continue;

            SlotMatchState state = InventoryLayoutMatcher.getState(
                    layout,
                    minecraft.player,
                    inventorySlot,
                    manager.config()
            );

            int x = left + slot.x;
            int y = top + slot.y;
            InventoryLayoutSlot expected = layout.expectedAt(inventorySlot);

            renderSlotState(graphics, x, y, state, expected, minecraft, manager);
        }

        renderStatusPanel(graphics, screen, manager, minecraft, hudLayoutManager);
    }

    private static void renderSlotState(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            SlotMatchState state,
            InventoryLayoutSlot expected,
            Minecraft minecraft,
            InventoryLayoutManager manager
    ) {
        switch (state) {
            case CORRECT -> {
                if (manager.config().showCorrectSlots) {
                    graphics.fill(x, y, x + 16, y + 16, CORRECT_FILL);
                    InventoryLayoutRenderUtil.drawBorder(graphics, x, y, 16, 16, CORRECT_BORDER);
                }
            }
            case CORRECT_EMPTY -> {

            }
            case WRONG_ITEM -> {
                graphics.fill(x, y, x + 16, y + 16, WRONG_FILL);
                InventoryLayoutRenderUtil.drawBorder(graphics, x, y, 16, 16, WRONG_BORDER);
            }
            case UNEXPECTED_ITEM -> {
                graphics.fill(x, y, x + 16, y + 16, UNEXPECTED_FILL);
                InventoryLayoutRenderUtil.drawBorder(graphics, x, y, 16, 16, UNEXPECTED_BORDER);
            }
            case MISSING_ITEM -> {
                graphics.fill(x, y, x + 16, y + 16, MISSING_FILL);
                InventoryLayoutRenderUtil.drawBorder(graphics, x, y, 16, 16, MISSING_BORDER);
                renderExpectedGhost(graphics, x, y, expected, minecraft, manager);
            }
        }
    }

    private static void renderExpectedGhost(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            InventoryLayoutSlot expected,
            Minecraft minecraft,
            InventoryLayoutManager manager
    ) {
        ItemStack ghost = InventoryLayoutRenderUtil.findRepresentativeStack(
                expected,
                minecraft.player,
                manager.config()
        );

        if (ghost.isEmpty()) {
            InventoryLayoutRenderUtil.drawMissingMarker(graphics, x, y);
            return;
        }

        InventoryLayoutRenderUtil.renderGhostItem(graphics, ghost, x, y);
    }

    private static void renderStatusPanel(
            GuiGraphicsExtractor graphics,
            InventoryScreen screen,
            InventoryLayoutManager manager,
            Minecraft minecraft,
            HudLayoutManager hudLayoutManager
    ) {
        InventoryLayoutMatcher.LayoutProgress progress = manager.progress(minecraft.player);
        String lineOne = "Layout: " + manager.activeLayout().name();
        String lineTwo = progress.correctSlots() + "/" + progress.totalSlots() + " slots correct";

        int left = (screen.width - INVENTORY_GUI_WIDTH) / 2;
        int top = (screen.height - INVENTORY_GUI_HEIGHT) / 2;
        int panelWidth = Math.max(minecraft.font.width(lineOne), minecraft.font.width(lineTwo)) + 12;
        int panelHeight = 30;
        int panelX = left + INVENTORY_GUI_WIDTH + 4;
        int panelY = top + 50;

        if (panelX + panelWidth > screen.width - 4) {
            panelX = Math.max(4, left - panelWidth - 4);
        }

        float scale = 1.0F;

        if (hudLayoutManager != null) {
            HudElement element = hudLayoutManager.get(HudLayoutManager.INVENTORY_LAYOUTS_PROGRESS);
            if (element != null) {
                boolean wasSeen = element.hasBeenSeen();

                if (!wasSeen) {
                    // Preserve the old automatic position until the player moves it in /nsm gui.
                    element.setPosition(panelX, panelY);
                }

                element.setMeasuredSize(panelWidth, panelHeight);
                panelX = element.getX();
                panelY = element.getY();
                scale = (float) element.getScale();

                if (!wasSeen) {
                    hudLayoutManager.save();
                }
            }
        }

        graphics.pose().pushMatrix();
        graphics.pose().translate((float) panelX, (float) panelY);
        graphics.pose().scale(scale, scale);

        graphics.fill(0, 0, panelWidth, panelHeight, 0xCC101218);
        graphics.fill(0, 0, 2, panelHeight, 0xFF55CCFF);
        graphics.text(minecraft.font, lineOne, 6, 5, 0xFFFFFFFF, true);
        graphics.text(minecraft.font, lineTwo, 6, 17, 0xFFCCCCCC, true);

        graphics.pose().popMatrix();
    }

    public static boolean captureLayoutTooltip(
            InventoryScreen screen,
            Slot slot,
            int mouseX,
            int mouseY,
            InventoryLayoutManager manager
    ) {
        pendingTooltip = null;

        if (screen == null || slot == null || manager == null || !manager.config().enabled) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        InventoryLayout layout = manager.activeLayout();
        if (layout == null || minecraft.player == null) return false;

        int inventorySlot = getPlayerInventorySlot(screen, slot);
        if (inventorySlot < 0) return false;

        SlotMatchState state = InventoryLayoutMatcher.getState(
                layout,
                minecraft.player,
                inventorySlot,
                manager.config()
        );

        if (state == SlotMatchState.CORRECT_EMPTY) return false;

        InventoryLayoutSlot expected = layout.expectedAt(inventorySlot);
        ItemStack actual = slot.getItem();
        List<Component> tooltipLines = getTooltipLines(expected, actual, state);
        if (tooltipLines.isEmpty()) return false;

        pendingTooltip = new PendingLayoutTooltip(screen, List.copyOf(tooltipLines), mouseX, mouseY);
        return true;
    }

    public static void renderPendingLayoutTooltip(
            InventoryScreen screen,
            GuiGraphicsExtractor graphics
    ) {
        PendingLayoutTooltip pending = pendingTooltip;
        pendingTooltip = null;

        if (pending == null || pending.screen() != screen || graphics == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        // The layout overlay itself uses a later stratum than the inventory. Put
        // the compact tooltip one stratum beyond that so slot borders, ghost
        // items and the progress HUD can never render on top of it.
        graphics.nextStratum();
        renderCompactTooltip(
                graphics,
                screen,
                minecraft,
                pending.lines(),
                pending.mouseX(),
                pending.mouseY()
        );
    }

    private static void renderCompactTooltip(
            GuiGraphicsExtractor graphics,
            InventoryScreen screen,
            Minecraft minecraft,
            List<Component> lines,
            int mouseX,
            int mouseY
    ) {
        List<FormattedCharSequence> rendered = lines.stream()
                .map(Component::getVisualOrderText)
                .toList();

        int contentWidth = 0;
        for (FormattedCharSequence line : rendered) {
            contentWidth = Math.max(contentWidth, minecraft.font.width(line));
        }

        int width = contentWidth + 10;
        int height = rendered.size() * 10 + 6;
        int x = mouseX + 12;
        int y = mouseY - 12;

        if (x + width > screen.width - 4) {
            x = mouseX - width - 12;
        }
        if (y + height > screen.height - 4) {
            y = mouseY - height - 4;
        }
        x = Math.max(4, x);
        y = Math.max(4, y);

        // Compact vanilla-style tooltip box. The Component formatting on each
        // line is preserved, including the item's display-name color.
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xF0100010);
        graphics.fill(x, y, x + width, y + height, 0xF0100010);
        graphics.fill(x - 1, y - 1, x + width + 1, y, 0xFF505000);
        graphics.fill(x - 1, y + height, x + width + 1, y + height + 1, 0xFF280028);
        graphics.fill(x - 1, y, x, y + height, 0xFF505000);
        graphics.fill(x + width, y, x + width + 1, y + height, 0xFF280028);

        int lineY = y + 3;
        for (FormattedCharSequence line : rendered) {
            graphics.text(minecraft.font, line, x + 5, lineY, 0xFFFFFFFF, true);
            lineY += 10;
        }
    }

    private static int getPlayerInventorySlot(InventoryScreen screen, Slot slot) {
        int menuSlotIndex = screen.getMenu().slots.indexOf(slot);
        if (menuSlotIndex < 9 || menuSlotIndex > 44) {
            return -1;
        }

        int inventorySlot = slot.getContainerSlot();
        return inventorySlot >= 0 && inventorySlot < InventoryLayout.INVENTORY_SLOT_COUNT
                ? inventorySlot
                : -1;
    }

    private static List<Component> getTooltipLines(
            InventoryLayoutSlot expected,
            ItemStack actual,
            SlotMatchState state
    ) {
        List<Component> lines = new ArrayList<>(2);

        if (actual != null && !actual.isEmpty()) {
            lines.add(actual.getHoverName().copy());
        } else if (expected != null) {
            lines.add(Component.literal(expected.displayName()).withStyle(ChatFormatting.WHITE));
        } else {
            lines.add(Component.literal("Empty slot").withStyle(ChatFormatting.GRAY));
        }

        switch (state) {
            case CORRECT -> lines.add(Component.literal("Correct slot").withStyle(ChatFormatting.GREEN));
            case WRONG_ITEM -> lines.add(Component.literal(
                    expected == null ? "Wrong item" : "Expected: " + expected.displayName()).withStyle(ChatFormatting.RED));
            case UNEXPECTED_ITEM -> lines.add(Component.literal("This slot should be empty").withStyle(ChatFormatting.RED));
            case MISSING_ITEM -> lines.add(Component.literal("Missing from this slot").withStyle(ChatFormatting.AQUA));
            case CORRECT_EMPTY -> { }
        }

        return lines;
    }

    private record PendingLayoutTooltip(
            InventoryScreen screen,
            List<Component> lines,
            int mouseX,
            int mouseY
    ) { }
}
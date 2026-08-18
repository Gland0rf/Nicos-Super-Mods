package com.nico.client.inventoryLayouts.render;

import com.nico.client.inventoryLayouts.core.InventoryLayout;
import com.nico.client.inventoryLayouts.core.InventoryLayoutManager;
import com.nico.client.inventoryLayouts.core.InventoryLayoutSlot;
import com.nico.client.inventoryLayouts.storage.InventoryLayoutMatcher;
import com.nico.client.inventoryLayouts.storage.SlotMatchState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;


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

    private InventoryLayoutOverlay() { }

    public static void render(
            InventoryScreen screen,
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            InventoryLayoutManager manager
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        InventoryLayout layout = manager.activeLayout();

        if (!manager.config().enabled || layout == null || minecraft.player == null) {
            return;
        }

        graphics.nextStratum();

        int left = (screen.width - INVENTORY_GUI_WIDTH) / 2;
        int top = (screen.height - INVENTORY_GUI_HEIGHT) / 2;
        InventoryLayoutSlot hoveredExpected = null;
        SlotMatchState hoveredState = null;

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

            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                hoveredExpected = expected;
                hoveredState = state;
            }
        }

        renderStatusPanel(graphics, screen, manager, minecraft);

        if (hoveredState != null && hoveredState != SlotMatchState.CORRECT_EMPTY) {
            String toolTipText = getTooltipText(hoveredExpected, hoveredState);

            if (toolTipText != null && !toolTipText.isBlank()) {
                graphics.setTooltipForNextFrame(
                        minecraft.font,
                        Component.literal(toolTipText),
                        mouseX,
                        mouseY
                );
            }
        }
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
                renderExpectedGhost(graphics, x, y, expected, minecraft, manager);
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
            Minecraft minecraft
    ) {
        InventoryLayoutMatcher.LayoutProgress progress = manager.progress(minecraft.player);
        String lineOne = "Layout: " + manager.activeLayout().name();
        String lineTwo = progress.correctSlots() + "/" + progress.totalSlots() + " slots correct";

        int left = (screen.width - INVENTORY_GUI_WIDTH) / 2;
        int top = (screen.height - INVENTORY_GUI_HEIGHT) / 2;
        int panelWidth = Math.max(minecraft.font.width(lineOne), minecraft.font.width(lineTwo)) + 12;
        int panelX = left + INVENTORY_GUI_WIDTH + 4;

        if (panelX + panelWidth > screen.width - 4) {
            panelX = Math.max(4, left - panelWidth - 4);
        }

        int panelY = top + 50;
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 30, 0xCC101218);
        graphics.fill(panelX, panelY, panelX + 2, panelY + 30, 0xFF55CCFF);
        graphics.text(minecraft.font, lineOne, panelX + 6, panelY + 5, 0xFFFFFFFF, true);
        graphics.text(minecraft.font, lineTwo, panelX + 6, panelY + 17, 0xFFCCCCCC, true);
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

    private static String getTooltipText(
            InventoryLayoutSlot expected,
            SlotMatchState state
    ) {
        return switch (state) {
            case CORRECT -> expected == null ? "Correct" : "Correct: " + expected.displayName();
            case WRONG_ITEM -> expected == null ? "Wrong item" : "Expected: " + expected.displayName();
            case UNEXPECTED_ITEM -> "This slot should be empty";
            case MISSING_ITEM -> expected == null ? "Missing item" : "Missing: " + expected.displayName();
            case CORRECT_EMPTY -> "Correctly empty";
        };
    }
}
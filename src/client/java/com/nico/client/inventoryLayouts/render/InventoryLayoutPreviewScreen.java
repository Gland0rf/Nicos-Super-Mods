package com.nico.client.inventoryLayouts.render;

import com.nico.client.inventoryLayouts.core.InventoryLayout;
import com.nico.client.inventoryLayouts.core.InventoryLayoutManager;
import com.nico.client.inventoryLayouts.core.InventoryLayoutSlot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class InventoryLayoutPreviewScreen extends Screen {
    private static final int SLOT_SIZE = 18;
    private static final int GRID_WIDTH = 9 * SLOT_SIZE;
    private static final int GRID_HEIGHT = 4 * SLOT_SIZE;

    private final Screen menuParent;
    private final Screen inventoryParent;
    private final InventoryLayout layout;
    private final InventoryLayoutManager manager;

    public InventoryLayoutPreviewScreen(
            Screen menuParent,
            Screen inventoryParent,
            InventoryLayout layout,
            InventoryLayoutManager manager
    ) {
        super(Component.literal("View Inventory Layout"));
        this.menuParent = menuParent;
        this.inventoryParent = inventoryParent;
        this.layout = layout;
        this.manager = manager;
    }

    @Override
    protected void init() {
        addRenderableWidget(
                Button.builder(Component.literal("Load"), button -> {
                            manager.activate(layout);
                            Minecraft.getInstance().setScreen(inventoryParent);
                        })
                        .bounds(width / 2 - 102, height - 34, 96, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.literal("Back"), button -> onClose())
                        .bounds(width / 2 + 6, height - 34, 96, 20)
                        .build()
        );
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xE0101218);
        graphics.centeredText(font, layout.name(), width / 2, 18, 0xFF55CCFF);
        graphics.centeredText(
                font,
                "Main inventory (top 3 rows) and hotbar (bottom row)",
                width / 2,
                32,
                0xFFAAAAAA
        );

        int gridX = (width - GRID_WIDTH) / 2;
        int gridY = 58;
        InventoryLayoutSlot hovered = null;

        for (int visualSlot = 0; visualSlot < InventoryLayout.INVENTORY_SLOT_COUNT; visualSlot++) {
            int inventorySlot = visualToInventorySlot(visualSlot);
            int column = visualSlot % 9;
            int row = visualSlot / 9;
            int x = gridX + column * SLOT_SIZE;
            int y = gridY + row * SLOT_SIZE;

            graphics.fill(x, y, x + 18, y + 1, 0xFF2A2D38);
            InventoryLayoutRenderUtil.drawBorder(graphics, x, y, 18, 18, 0xFF555A68);

            InventoryLayoutSlot expected = layout.expectedAt(inventorySlot);
            if (expected != null) {
                ItemStack representative = InventoryLayoutRenderUtil.findRepresentativeStack(
                        expected,
                        Minecraft.getInstance().player,
                        manager.config()
                );
                if (representative.isEmpty()) {
                    InventoryLayoutRenderUtil.drawMissingMarker(graphics, x + 1, y + 1);
                } else {
                    graphics.item(representative, x + 1, y + 1);
                }
            }

            if (mouseX >= x && mouseX < x + 18 && mouseY >= y && mouseY < y + 18) {
                hovered = expected;
            }
        }

        if (hovered != null) {
            graphics.setTooltipForNextFrame(font, Component.literal(hovered.displayName()), mouseX, mouseY);
        }

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(menuParent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static int visualToInventorySlot(int visualSlot) {
        int row = visualSlot / 9;
        int column = visualSlot % 9;

        if (row == 3) {
            return column;
        }

        return 9 + row * 9 + column;
    }
}

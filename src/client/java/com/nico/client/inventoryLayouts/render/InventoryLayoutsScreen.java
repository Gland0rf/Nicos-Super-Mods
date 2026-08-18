package com.nico.client.inventoryLayouts.render;

import com.nico.client.inventoryLayouts.core.InventoryLayout;
import com.nico.client.inventoryLayouts.core.InventoryLayoutManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class InventoryLayoutsScreen extends Screen {
    private static final int MAX_ROWS_PER_PAGE = 7;
    private static final int ROW_HEIGHT = 26;

    private final Screen inventoryParent;
    private final InventoryLayoutManager manager;
    private int page;

    public InventoryLayoutsScreen(Screen inventoryParent, InventoryLayoutManager manager) {
        super(Component.literal("Inventory Layouts"));
        this.inventoryParent = inventoryParent;
        this.manager = manager;
    }

    @Override
    protected void init() {
        List<InventoryLayout> layouts = manager.storage().getLayouts();
        int rowsPerPage = rowsPerPage();
        int pageCount = pageCount(layouts.size(), rowsPerPage);
        page = Math.max(0, Math.min(page, pageCount - 1));

        int panelWidth = panelWidth();
        int left = (width - panelWidth) / 2;
        int rowTop = 50;
        int firstIndex = page * rowsPerPage;
        int lastIndex = Math.min(layouts.size(), firstIndex + rowsPerPage);

        for (int index = firstIndex; index < lastIndex; index++) {
            InventoryLayout layout = layouts.get(index);
            int row = index - firstIndex;
            int y = rowTop + row * ROW_HEIGHT;

            addRenderableWidget(
                    Button.builder(Component.literal("Load"), button -> {
                                manager.activate(layout);
                                Minecraft.getInstance().setScreen(inventoryParent);
                            })
                            .bounds(left + panelWidth - 174, y, 52, 20)
                            .build()
            );

            addRenderableWidget(
                    Button.builder(Component.literal("Preview"), button ->
                                    Minecraft.getInstance().setScreen(
                                            new InventoryLayoutPreviewScreen(
                                                    this,
                                                    inventoryParent,
                                                    layout,
                                                    manager
                                            )
                                    ))
                            .bounds(left + panelWidth - 116, y, 52, 20)
                            .build()
            );

            addRenderableWidget(
                    Button.builder(Component.literal("Delete"), button -> {
                                manager.delete(layout);
                                InventoryLayoutsScreen.this.rebuildWidgets();
                            })
                            .bounds(left + panelWidth - 58, y, 52, 20)
                            .build()
            );
        }

        addFooterButtons(left, panelWidth);

        addRenderableWidget(
                Button.builder(Component.literal("<"), button -> {
                            page = Math.max(0, page - 1);
                            InventoryLayoutsScreen.this.rebuildWidgets();
                        })
                        .bounds(width / 2 - 80, height - 29, 24, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.literal(">"), button -> {
                            page = Math.min(pageCount - 1, page + 1);
                            InventoryLayoutsScreen.this.rebuildWidgets();
                        })
                        .bounds(width / 2 + 56, height - 29, 24, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.literal("Close"), button -> onClose())
                        .bounds(width / 2 - 40, height - 29, 80, 20)
                        .build()
        );
    }

    private void addFooterButtons(int left, int panelWidth) {
        int footerTop = height - 80;
        int saveWidth = 112;
        int stopWidth = 100;
        int gap = 8;
        int totalWidth = manager.activeLayout() == null
                ? saveWidth
                : saveWidth + gap + stopWidth;
        int startX = left + Math.max(0, (panelWidth - totalWidth) / 2);

        addRenderableWidget(
                Button.builder(Component.literal("Save current"), button ->
                                Minecraft.getInstance().setScreen(
                                        new SaveInventoryLayoutScreen(this, inventoryParent, manager)
                                ))
                        .bounds(startX, footerTop, saveWidth, 20)
                        .build()
        );

        if (manager.activeLayout() != null) {
            addRenderableWidget(
                    Button.builder(Component.literal("Stop active"), button -> {
                                manager.deactivate(true);
                                InventoryLayoutsScreen.this.rebuildWidgets();
                            })
                            .bounds(startX + saveWidth + gap, footerTop, stopWidth, 20)
                            .build()
            );
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xE0101218);

        int panelWidth = panelWidth();
        int left = (width - panelWidth) / 2;
        int panelTop = 32;
        int panelBottom = height - 86;
        graphics.fill(left, panelTop, left + panelWidth, panelBottom, 0xCC1A1D28);
        graphics.fill(left, panelTop, left + 3, panelBottom, 0xFF55CCFF);

        graphics.centeredText(font, title, width / 2, 16, 0xFF55CCFF);

        List<InventoryLayout> layouts = manager.storage().getLayouts();
        int rowsPerPage = rowsPerPage();
        int pageCount = pageCount(layouts.size(), rowsPerPage);
        int firstIndex = page * rowsPerPage;
        int lastIndex = Math.min(layouts.size(), firstIndex + rowsPerPage);

        if (layouts.isEmpty()) {
            graphics.centeredText(
                    font,
                    "No layouts saved yet. Open your inventory and save the current setup.",
                    width / 2,
                    78,
                    0xFFBBBBBB
            );
        } else {
            for (int index = firstIndex; index < lastIndex; index++) {
                InventoryLayout layout = layouts.get(index);
                int row = index - firstIndex;
                int y = 56 + row * ROW_HEIGHT;
                int textColor = manager.isActive(layout) ? 0xFF55FF88 : 0xFFFFFFFF;

                graphics.text(font, layout.name(), left + 10, y, textColor, true);
                graphics.text(
                        font,
                        layout.itemSlotCount() + " occupied slots",
                        left + 10,
                        y + 10,
                        0xFF999999,
                        false
                );
            }
        }

        graphics.centeredText(
                font,
                "Page " + (page + 1) + "/" + pageCount,
                width / 2,
                height - 23,
                0xFFAAAAAA
        );

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(inventoryParent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int panelWidth() {
        return Math.max(1, Math.min(500, width - 24));
    }

    private int rowsPerPage() {
        int availableHeight = Math.max(ROW_HEIGHT, height - 136);
        return Math.max(1, Math.min(MAX_ROWS_PER_PAGE, availableHeight / ROW_HEIGHT));
    }

    private static int pageCount(int layoutCount, int rowsPerPage) {
        return Math.max(1, (layoutCount + rowsPerPage - 1) / rowsPerPage);
    }
}
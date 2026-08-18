package com.nico.client.wiki.screen;

import static com.nico.client.wiki.screen.WikiScreenMetrics.*;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiContent;
import com.nico.client.wiki.WikiCraftingGrid;
import com.nico.client.wiki.WikiImage;
import com.nico.client.wiki.WikiImageTextureCache;
import com.nico.client.wiki.WikiInfobox;
import com.nico.client.wiki.WikiItemSlot;
import com.nico.client.wiki.WikiText;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Draws tables, infoboxes, tabs, crafting widgets, images, and slots. */
abstract class WikiScreenWidgetRenderer extends WikiScreenRenderer {
    protected WikiScreenWidgetRenderer(Screen parent, ItemStack itemStack) {
        super(parent, itemStack);
    }

    protected void renderEntry(GuiGraphicsExtractor graphics, RenderEntry entry, int y) {
        switch (entry.kind()) {
            case PAGE_TITLE -> {
                drawCells(graphics, entry, y, TEXT);
                graphics.fill(entry.x(), y + entry.height() - 1, entry.x() + entry.width(), y + entry.height(), DIVIDER);
            }
            case MESSAGEBOX -> renderMessageBox(graphics, entry, y);
            case TEXT -> drawCells(graphics, entry, y, TEXT);
            case H2, H3 -> {
                drawCells(graphics, entry, y, TEXT);
                graphics.fill(entry.x(), y + entry.height() - 2, entry.x() + entry.width(), y + entry.height() - 1,
                        entry.kind() == Kind.H2 ? DIVIDER : BORDER);
            }
            case HR -> graphics.fill(entry.x(), y, entry.x() + entry.width(), y + 1, DIVIDER);
            case TOC_HEADER -> {
                graphics.fill(entry.x(), y, entry.x() + entry.width(), y + entry.height(), tocBackgroundColor());
                drawCells(graphics, entry, y, TEXT);
                graphics.fill(entry.x() + 10, y + entry.height() - 1,
                        entry.x() + entry.width() - 10, y + entry.height(), LINK);
            }
            case TOC_ROW -> {
                boolean hovered = contains(renderMouseX, renderMouseY, entry.x(), y, entry.width(), entry.height());
                graphics.fill(entry.x(), y, entry.x() + entry.width(), y + entry.height(),
                        hovered ? tocHoverColor() : tocBackgroundColor());
                drawCells(graphics, entry, y, LINK);
                TocItem item = (TocItem) entry.payload();
                tocHitboxes.add(new TocHitbox(entry.x(), y, entry.width(), entry.height(), item));
            }
            case TABLE -> renderTable(graphics, entry, y);
            case TABLE_ROW -> renderTableRow(graphics, entry, y);
            case INFOBOX_TITLE -> {
                graphics.fill(entry.x(), y, entry.x() + entry.width(), y + entry.height(), BLUE);
                graphics.fill(entry.x(), y, entry.x() + entry.width(), y + 3, entry.aux());
                drawCenteredCells(graphics, entry, y, TEXT);
                renderBorder(graphics, entry, y, BORDER);
            }
            case INFOBOX_IMAGE -> renderInfoboxImage(graphics, entry, y);
            case INFOBOX_SLOTS -> {
                renderSlotStrip(graphics, entry, y,
                        ((WikiInfobox.SlotStrip) entry.payload()).slots());
                renderBorder(graphics, entry, y, BORDER);
            }
            case INFOBOX_TABS -> renderInfoboxTabs(graphics, entry, y);
            case INFOBOX_HEADER -> {
                graphics.fill(entry.x(), y, entry.x() + entry.width(), y + entry.height(), BLUE_DARK);
                drawCenteredCells(graphics, entry, y, TEXT);
                renderBorder(graphics, entry, y, BORDER);
            }
            case INFOBOX_ROW -> renderInfoboxRow(graphics, entry, y);
            case INFOBOX_GRID -> renderInfoboxGrid(graphics, entry, y);
            case SLOT_STRIP -> renderSlotStrip(graphics, entry, y, castSlots(entry.payload()));
            case IMAGE -> renderWikiImageEntry(graphics, entry, y);
            case TABS -> renderTabs(graphics, entry, y);
            case TAB_BORDER -> renderBorder(graphics, entry, y, BORDER);
            case CRAFTING -> renderCrafting(graphics, entry, y, (WikiCraftingGrid) entry.payload());
            case FORGING_TREE -> renderForgingTree(graphics, entry, y);
        }
    }

    protected void renderForgingTree(GuiGraphicsExtractor graphics, RenderEntry entry, int y) {
        if (!(entry.payload() instanceof ForgingTreeLayout layout)) {
            return;
        }

        final int indentStep = 16;
        for (ForgingTreeRow row : layout.rows()) {
            int rowY = y + row.yOffset();
            int middleY = rowY + 7;

            boolean[] ancestors = row.ancestorContinues();
            for (int depth = 0; depth < ancestors.length - 1; depth++) {
                if (!ancestors[depth]) {
                    continue;
                }
                int lineX = entry.x() + depth * indentStep + 7;
                graphics.fill(lineX, rowY, lineX + 1, rowY + row.height() + 2, DIVIDER);
            }

            if (row.depth() > 0) {
                int parentLineX = entry.x() + (row.depth() - 1) * indentStep + 7;
                int verticalTop = row.parentMiddleYOffset() >= 0
                        ? y + row.parentMiddleYOffset()
                        : rowY;
                int verticalBottom = row.lastSibling()
                        ? middleY + 1
                        : rowY + row.height() + 2;
                graphics.fill(parentLineX, verticalTop, parentLineX + 1, verticalBottom, DIVIDER);
                graphics.fill(
                        parentLineX,
                        middleY,
                        entry.x() + row.iconXOffset(),
                        middleY + 1,
                        DIVIDER
                );
            }

            if (!row.node().content().itemSlots().isEmpty()) {
                drawForgingTreeIcon(
                        graphics,
                        row.node().content().itemSlots().get(0),
                        entry.x() + row.iconXOffset(),
                        rowY,
                        14
                );
            }

            int lineY = rowY + 1;
            for (FormattedCharSequence line : row.lines()) {
                drawInteractiveLine(
                        graphics,
                        line,
                        entry.x() + row.textXOffset(),
                        lineY,
                        TEXT
                );
                lineY += LINE_HEIGHT;
            }

            if (row.node().expandable() && row.depth() > 0) {
                String label = row.expanded() ? "[Collapse]" : "[Expand]";
                int toggleX = entry.x() + row.toggleXOffset();
                boolean hovered = contains(
                        renderMouseX,
                        renderMouseY,
                        toggleX,
                        rowY,
                        row.toggleWidth(),
                        13
                );
                graphics.text(
                        font,
                        Component.literal(label),
                        toggleX,
                        rowY + 1,
                        hovered ? TEXT : LINK,
                        false
                );
                forgingTreeHitboxes.add(new ForgingTreeHitbox(
                        toggleX,
                        rowY,
                        row.toggleWidth(),
                        13,
                        row.stateKey(),
                        row.expanded()
                ));
            }
        }
    }

    protected void drawForgingTreeIcon(
            GuiGraphicsExtractor graphics,
            WikiItemSlot slot,
            int x,
            int y,
            int size
    ) {
        if (slot == null || slot.isEmpty()) {
            return;
        }

        WikiItemSlot.Frame frame = displayedFrame(slot);
        slotHitboxes.add(new SlotHitbox(x, y, size, size, frame));
        WikiImage image = frame.image();
        if (image == null || image.isEmpty()) {
            return;
        }

        WikiImageTextureCache.Snapshot snapshot = WikiImageTextureCache.request(image);
        if (!snapshot.ready()) {
            return;
        }

        int drawWidth = size;
        int drawHeight = Math.max(1, (int) Math.round(
                (double) drawWidth * snapshot.height() / snapshot.width()
        ));
        if (drawHeight > size) {
            drawHeight = size;
            drawWidth = Math.max(1, (int) Math.round(
                    (double) drawHeight * snapshot.width() / snapshot.height()
            ));
        }

        int drawX = x + (size - drawWidth) / 2;
        int drawY = y + (size - drawHeight) / 2;
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                snapshot.textureId(),
                drawX,
                drawY,
                0.0F,
                0.0F,
                drawWidth,
                drawHeight,
                snapshot.width(),
                snapshot.height(),
                snapshot.width(),
                snapshot.height()
        );

        if (contains(renderMouseX, renderMouseY, x, y, size, size)) {
            graphics.fill(x, y, x + size, y + 1, LINK);
            graphics.fill(x, y + size - 1, x + size, y + size, LINK);
            graphics.fill(x, y, x + 1, y + size, LINK);
            graphics.fill(x + size - 1, y, x + size, y + size, LINK);
        }
    }

    protected void renderMessageBox(GuiGraphicsExtractor graphics, RenderEntry entry, int y) {
        if (!(entry.payload() instanceof MessageBoxLayout layout)) {
            return;
        }

        int accent = switch (layout.box().tone()) {
            case GREEN -> MESSAGEBOX_GREEN;
            case RED -> MESSAGEBOX_RED;
            case BLUE -> MESSAGEBOX_BLUE;
            case YELLOW -> MESSAGEBOX_YELLOW;
            case ORANGE -> MESSAGEBOX_ORANGE;
            case PURPLE -> MESSAGEBOX_PURPLE;
            case GRAY -> MESSAGEBOX_GRAY;
            case DEFAULT -> BORDER;
        };

        graphics.fill(entry.x(), y, entry.x() + entry.width(), y + entry.height(), MESSAGEBOX_BACKGROUND);
        graphics.fill(entry.x(), y, entry.x() + 4, y + entry.height(), accent);
        renderBorder(graphics, entry, y, accent);

        WikiContent content = layout.box().content();
        int textX = entry.x() + 10;
        if (layout.iconWidth() > 0 && !content.images().isEmpty()) {
            int iconY = y + Math.max(6, (entry.height() - layout.iconHeight()) / 2);
            drawRemoteImage(
                    graphics,
                    content.images().get(0),
                    entry.x() + 10,
                    iconY,
                    layout.iconWidth(),
                    layout.iconHeight()
            );
            textX += layout.iconWidth() + 8;
        }

        int textY = y + Math.max(7, (entry.height() - layout.lines().size() * LINE_HEIGHT) / 2);
        for (FormattedCharSequence line : layout.lines()) {
            drawInteractiveLine(graphics, line, textX, textY, TEXT);
            textY += LINE_HEIGHT;
        }
    }

    protected void renderTable(GuiGraphicsExtractor graphics, RenderEntry entry, int y) {
        if (!(entry.payload() instanceof TableLayout table)) {
            return;
        }

        int rowY = y;
        int rowOffset = 0;
        boolean alternate = false;
        boolean renderedBodyGroup = false;
        for (int row = 0; row < table.rowHeights().length; row++) {
            boolean header = table.headerRows()[row];

            boolean continuesPreviousRow = false;
            if (row > 0) {
                for (RenderedTableCell cell : table.cells()) {
                    if (cell.yOffset() < rowOffset && cell.yOffset() + cell.height() > rowOffset) {
                        continuesPreviousRow = true;
                        break;
                    }
                }
            }
            if (!header && renderedBodyGroup && !continuesPreviousRow) {
                alternate = !alternate;
            }

            int background = header ? tableHeadColor() : (alternate ? tableAltColor() : tableRowColor());
            graphics.fill(entry.x(), rowY, entry.x() + entry.width(), rowY + table.rowHeights()[row], background);
            if (!header) {
                renderedBodyGroup = true;
            }
            rowY += table.rowHeights()[row];
            rowOffset += table.rowHeights()[row];
        }

        for (RenderedTableCell cell : table.cells()) {
            int cellX = entry.x() + cell.xOffset();
            int cellY = y + cell.yOffset();
            if (cell.header()) {
                graphics.fill(cellX, cellY, cellX + cell.width(), cellY + cell.height(), tableHeadColor());
            }

            graphics.fill(cellX, cellY, cellX + cell.width(), cellY + 1, BORDER);
            graphics.fill(cellX, cellY + cell.height() - 1, cellX + cell.width(), cellY + cell.height(), BORDER);
            graphics.fill(cellX, cellY, cellX + 1, cellY + cell.height(), BORDER);
            graphics.fill(cellX + cell.width() - 1, cellY, cellX + cell.width(), cellY + cell.height(), BORDER);

            // Keep rich table widgets inside their own cell. The Wiki has a
            // number of compact crafting/showcase cells whose content used to
            // spill over the right border at narrow GUI scales.
            graphics.enableScissor(
                    cellX + 1,
                    cellY + 1,
                    cellX + cell.width() - 1,
                    cellY + cell.height() - 1
            );

            int contentX = cellX + 5;
            int innerWidth = Math.max(12, cell.width() - 10);
            int contentY = cellY + Math.max(4, (cell.height() - cell.contentHeight()) / 2);
            for (FormattedCharSequence line : cell.lines()) {
                drawInteractiveLine(graphics, line, contentX, contentY, TEXT);
                contentY += LINE_HEIGHT;
            }

            WikiContent richContent = cell.content();
            if (!cell.lines().isEmpty()
                    && (!richContent.itemSlots().isEmpty()
                    || !richContent.craftingGrids().isEmpty()
                    || !richContent.images().isEmpty())) {
                contentY += 3;
            }

            contentY = renderCompactSlots(graphics, richContent.itemSlots(), contentX, contentY, innerWidth);
            WikiCraftingGrid activeGrid = activeCraftingGrid(richContent.craftingGrids());
            if (activeGrid != null) {
                contentY = renderCompactCrafting(graphics, activeGrid, contentX, contentY, innerWidth);
            }
            for (WikiImage image : richContent.images()) {
                int imageHeight = tableImageHeight(richContent, image, innerWidth);
                drawRemoteImage(graphics, image, contentX, contentY, innerWidth, imageHeight, isImageOnlyTableContent(richContent));
                contentY += imageHeight + 3;
            }
            graphics.disableScissor();
        }
    }

    protected void renderTableRow(GuiGraphicsExtractor graphics, RenderEntry entry, int y) {
        int bg = entry.aux() == 2 ? tableHeadColor() : entry.aux() == 1 ? tableAltColor() : tableRowColor();
        graphics.fill(entry.x(), y, entry.x() + entry.width(), y + entry.height(), bg);

        for (Cell cell : entry.cells()) {
            int divider = entry.x() + cell.xOffset() - 5;
            if (cell.xOffset() > 5) {
                graphics.fill(divider, y, divider + 1, y + entry.height(), BORDER);
            }

            int contentY = y + cell.yOffset();
            for (FormattedCharSequence line : cell.lines()) {
                drawInteractiveLine(graphics, line, entry.x() + cell.xOffset(), contentY, TEXT);
                contentY += LINE_HEIGHT;
            }

            WikiContent richContent = cell.richContent();
            if (richContent != null) {
                if (!cell.lines().isEmpty()
                        && (!richContent.itemSlots().isEmpty()
                        || !richContent.craftingGrids().isEmpty()
                        || !richContent.images().isEmpty())) {
                    contentY += 3;
                }

                contentY = renderCompactSlots(
                        graphics,
                        richContent.itemSlots(),
                        entry.x() + cell.xOffset(),
                        contentY,
                        cell.width()
                );

                WikiCraftingGrid activeGrid = activeCraftingGrid(richContent.craftingGrids());
                if (activeGrid != null) {
                    contentY = renderCompactCrafting(
                            graphics,
                            activeGrid,
                            entry.x() + cell.xOffset(),
                            contentY,
                            cell.width()
                    );
                }

                for (WikiImage image : richContent.images()) {
                    int imageHeight = tableImageHeight(richContent, image, cell.width());
                    drawRemoteImage(
                            graphics,
                            image,
                            entry.x() + cell.xOffset(),
                            contentY,
                            cell.width(),
                            imageHeight,
                            isImageOnlyTableContent(richContent)
                    );
                    contentY += imageHeight + 3;
                }
            }
        }

        graphics.fill(entry.x(), y + entry.height() - 1, entry.x() + entry.width(), y + entry.height(), BORDER);
    }

    protected int renderCompactSlots(
            GuiGraphicsExtractor graphics,
            List<WikiItemSlot> slots,
            int x,
            int y,
            int width
    ) {
        if (slots == null || slots.isEmpty()) {
            return y;
        }

        int step = compactSlotStep(width, slots.size());
        int slotSize = step - 2;
        int perRow = Math.max(1, width / step);

        boolean compactEquipmentRow = slots.size() >= 2
                && slots.size() <= 4
                && perRow >= 4;
        if (compactEquipmentRow) {
            int rowWidth = slots.size() * step;
            int startX = x + Math.max(0, (width - rowWidth) / 2);

            for (int index = 0; index < slots.size(); index++) {
                int slotX = startX + index * step;
                drawSlot(graphics, slotX, y, slotSize, slots.get(index));
            }

            return y + step + 3;
        }

        for (int index = 0; index < slots.size(); index++) {
            int slotX = x + (index % perRow) * step;
            int slotY = y + (index / perRow) * step;
            drawSlot(graphics, slotX, slotY, slotSize, slots.get(index));
        }

        int rows = (slots.size() + perRow - 1) / perRow;
        return y + rows * step + 3;
    }

    protected int renderCompactCrafting(
            GuiGraphicsExtractor graphics,
            WikiCraftingGrid grid,
            int x,
            int y,
            int width
    ) {
        int slotSize = compactCraftingSlotSize(width);
        int step = slotSize + 2;
        int gridWidth = step * 3;
        int outputSize = slotSize + 6;
        int totalWidth = gridWidth + 24 + outputSize;
        int startX = x + Math.max(0, (width - totalWidth) / 2);

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                drawSlot(
                        graphics,
                        startX + column * step,
                        y + row * step,
                        slotSize,
                        grid.input(row, column)
                );
            }
        }

        int arrowX = startX + gridWidth + 4;
        int arrowY = y + step + Math.max(0, slotSize / 3);
        graphics.centeredText(font, "->", arrowX, arrowY, MUTED);
        drawSlot(graphics, arrowX + 19, y + step - 3, outputSize, grid.output());
        return y + compactCraftingHeight(width) + 3;
    }

    protected void renderInfoboxImage(GuiGraphicsExtractor graphics, RenderEntry entry, int y) {
        graphics.fill(entry.x(), y, entry.x() + entry.width(), y + entry.height(), PAGE);
        WikiInfobox.Image image = (WikiInfobox.Image) entry.payload();
        int captionHeight = entry.aux();
        int imageBottom = y + entry.height() - captionHeight - 5;
        boolean rendered = drawRemoteImage(
                graphics,
                image.image(),
                entry.x() + 7,
                y + 6,
                entry.width() - 14,
                Math.max(20, imageBottom - y - 6)
        );
        if (!rendered && image.image().isEmpty()) {
            graphics.item(itemStack, entry.x() + entry.width() / 2 - 8, y + 18);
        }
        if (!image.caption().isBlank()) {
            String caption = font.plainSubstrByWidth(image.caption().plainText(), entry.width() - 12);
            graphics.centeredText(font, caption, entry.x() + entry.width() / 2,
                    y + entry.height() - captionHeight + 2, MUTED);
        }
        renderBorder(graphics, entry, y, BORDER);
    }

    protected void renderInfoboxTabs(GuiGraphicsExtractor graphics, RenderEntry entry, int y) {
        WikiInfobox.PanelTabs tabs = (WikiInfobox.PanelTabs) entry.payload();
        if (tabs.labels().isEmpty()) {
            return;
        }
        int tabWidth = Math.max(1, entry.width() / tabs.labels().size());
        for (int i = 0; i < tabs.labels().size(); i++) {
            int x = entry.x() + i * tabWidth;
            int right = i == tabs.labels().size() - 1 ? entry.x() + entry.width() : x + tabWidth;
            graphics.fill(x, y, right, y + entry.height(), i == tabs.activeIndex() ? BLUE : TAB);
            graphics.centeredText(font, font.plainSubstrByWidth(tabs.labels().get(i), right - x - 6),
                    (x + right) / 2, y + 6, TEXT);
            tabHitboxes.add(new TabHitbox(x, y, right - x, entry.height(), entry.aux(), i));
        }
        renderBorder(graphics, entry, y, BORDER);
    }

    protected void renderInfoboxRow(GuiGraphicsExtractor graphics, RenderEntry entry, int y) {
        int split = entry.aux();
        graphics.fill(entry.x(), y, entry.x() + split, y + entry.height(), TABLE_ROW);
        graphics.fill(entry.x() + split, y, entry.x() + entry.width(), y + entry.height(), PAGE);
        graphics.fill(entry.x() + split, y, entry.x() + split + 1, y + entry.height(), BORDER);
        graphics.fill(entry.x(), y, entry.x() + 1, y + entry.height(), BORDER);
        graphics.fill(entry.x() + entry.width() - 1, y, entry.x() + entry.width(), y + entry.height(), BORDER);
        graphics.fill(entry.x(), y + entry.height() - 1, entry.x() + entry.width(), y + entry.height(), BORDER);
        drawCells(graphics, entry, y, TEXT);
    }

    protected void renderInfoboxGrid(GuiGraphicsExtractor graphics, RenderEntry entry, int y) {
        if (!(entry.payload() instanceof InfoboxGridLayout layout)) {
            return;
        }
        graphics.fill(entry.x(), y, entry.x() + entry.width(), y + entry.height(), PAGE);
        for (InfoboxPropertyCell cell : layout.cells()) {
            int cellX = entry.x() + cell.xOffset();
            if (cell.xOffset() > 0) {
                graphics.fill(cellX, y, cellX + 1, y + entry.height(), BORDER);
            }
            int totalHeight = cell.labelLines().size() * LINE_HEIGHT
                    + cell.valueLines().size() * LINE_HEIGHT
                    + (cell.valueLines().isEmpty() ? 0 : 4);
            int lineY = y + Math.max(5, (entry.height() - totalHeight) / 2);
            for (FormattedCharSequence line : cell.labelLines()) {
                int lineX = cellX + Math.max(4, (cell.width() - font.width(line)) / 2);
                drawInteractiveLine(graphics, line, lineX, lineY, TEXT);
                lineY += LINE_HEIGHT;
            }
            if (!cell.valueLines().isEmpty()) {
                lineY += 4;
            }
            for (FormattedCharSequence line : cell.valueLines()) {
                int lineX = cellX + Math.max(4, (cell.width() - font.width(line)) / 2);
                drawInteractiveLine(graphics, line, lineX, lineY, TEXT);
                lineY += LINE_HEIGHT;
            }
        }
        renderBorder(graphics, entry, y, BORDER);
    }

    protected void renderSlotStrip(GuiGraphicsExtractor graphics, RenderEntry entry, int y, List<WikiItemSlot> slots) {
        graphics.fill(entry.x(), y, entry.x() + entry.width(), y + entry.height(), PAGE);
        int perRow = Math.max(1, Math.min(9, (entry.width() - 8) / 22));
        for (int i = 0; i < slots.size(); i++) {
            int sx = entry.x() + 4 + (i % perRow) * 22;
            int sy = y + 4 + (i / perRow) * 23;
            drawSlot(graphics, sx, sy, 20, slots.get(i));
        }
    }

    protected void renderTabs(GuiGraphicsExtractor graphics, RenderEntry entry, int y) {
        TabPayload payload = (TabPayload) entry.payload();
        for (TabButton button : payload.buttons()) {
            int buttonY = y + button.y() - entry.y();
            boolean active = button.index() == payload.selected();
            graphics.fill(button.x(), buttonY, button.x() + button.width(), buttonY + button.height(), active ? TAB_ACTIVE : TAB);
            renderBorder(graphics, new RenderEntry(Kind.HR, button.x(), buttonY, button.width(), button.height(), List.of(), 0, null),
                    buttonY, BORDER);
            String visibleTitle = font.plainSubstrByWidth(button.title(), button.width() - 8);
            drawCenteredFindHighlights(graphics, visibleTitle, button.x() + button.width() / 2, buttonY + 5);
            graphics.centeredText(font, visibleTitle, button.x() + button.width() / 2, buttonY + 5, TEXT);
            tabHitboxes.add(new TabHitbox(button.x(), buttonY, button.width(), button.height(), entry.aux(), button.index()));
        }
    }

    protected void renderCrafting(GuiGraphicsExtractor graphics, RenderEntry entry, int y, WikiCraftingGrid grid) {
        int left = entry.x();
        int right = entry.x() + entry.width();
        int bottom = y + entry.height();

        graphics.fill(left, y, right, bottom, CRAFTING_BACKGROUND);
        graphics.fill(left, y, right, y + 2, CRAFTING_BORDER_LIGHT);
        graphics.fill(left, y, left + 2, bottom, CRAFTING_BORDER_LIGHT);
        graphics.fill(left, bottom - 2, right, bottom, CRAFTING_BORDER_DARK);
        graphics.fill(right - 2, y, right, bottom, CRAFTING_BORDER_DARK);

        graphics.text(font, grid.shapeless() ? "Shapeless Crafting" : "Crafting Recipe",
                left + 8, y + 7, 0xFF3F3F3F, false);

        int gridX = left + 13;
        int gridY = y + 27;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                drawSlot(graphics, gridX + column * 22, gridY + row * 22, 20, grid.input(row, column));
            }
        }

        int arrowX = gridX + 76;
        int arrowY = gridY + 22;
        graphics.fill(arrowX, arrowY + 5, arrowX + 28, arrowY + 12, CRAFTING_ARROW);
        graphics.fill(arrowX + 20, arrowY, arrowX + 28, arrowY + 17, CRAFTING_ARROW);
        graphics.fill(arrowX + 27, arrowY + 4, arrowX + 34, arrowY + 13, CRAFTING_ARROW);

        drawSlot(graphics, arrowX + 43, gridY + 18, 30, grid.output());
    }

    protected void renderWikiImageEntry(GuiGraphicsExtractor graphics, RenderEntry entry, int y) {
        graphics.fill(entry.x(), y, entry.x() + entry.width(), y + entry.height(), SLOT);

        WikiImage image;
        WikiText caption;
        if (entry.payload() instanceof WikiBlock.Image blockImage) {
            image = blockImage.image();
            caption = blockImage.caption();
        } else if (entry.payload() instanceof WikiImage directImage) {
            image = directImage;
            caption = WikiText.empty();
        } else {
            image = WikiImage.empty();
            caption = WikiText.empty();
        }

        int captionHeight = entry.aux();
        int imageHeight = Math.max(12, entry.height() - captionHeight - 8);
        drawRemoteImage(graphics, image, entry.x() + 4, y + 4, entry.width() - 8, imageHeight);

        if (!caption.isBlank()) {
            String visible = font.plainSubstrByWidth(caption.plainText(), entry.width() - 10);
            int captionY = y + entry.height() - captionHeight + 1;
            int captionX = entry.x() + entry.width() / 2;
            drawCenteredFindHighlights(graphics, visible, captionX, captionY);
            graphics.centeredText(font, visible, captionX, captionY, MUTED);
        }

        renderBorder(graphics, entry, y, BORDER);
    }

    protected boolean drawRemoteImage(
            GuiGraphicsExtractor graphics,
            WikiImage image,
            int x,
            int y,
            int maxWidth,
            int maxHeight
    ) {
        return drawRemoteImage(graphics, image, x, y, maxWidth, maxHeight, false);
    }

    protected boolean drawRemoteImage(
            GuiGraphicsExtractor graphics,
            WikiImage image,
            int x,
            int y,
            int maxWidth,
            int maxHeight,
            boolean allowUpscale
    ) {
        if (image == null || image.isEmpty() || maxWidth <= 0 || maxHeight <= 0) {
            return false;
        }

        imageHitboxes.add(new ImageHitbox(x, y, maxWidth, maxHeight, image));
        WikiImageTextureCache.Snapshot snapshot = WikiImageTextureCache.request(image);
        if (!snapshot.ready()) {
            if (snapshot.status() == WikiImageTextureCache.Status.LICENSE_RESTRICTED) {
                drawLicenseProtectionImageMessage(graphics, x, y, maxWidth, maxHeight);
                return false;
            }
            String message = snapshot.status() == WikiImageTextureCache.Status.FAILED
                    ? (snapshot.error().isBlank() ? "Image failed" : snapshot.error())
                    : "Loading image...";
            if (message == null || message.isBlank()) {
                message = "Wiki image";
            }
            graphics.centeredText(font, font.plainSubstrByWidth(message, Math.max(8, maxWidth - 4)),
                    x + maxWidth / 2, y + maxHeight / 2 - 4, MUTED);
            return false;
        }

        int preferredWidth = allowUpscale
                ? maxWidth
                : image.declaredWidth() > 0
                ? Math.min(maxWidth, image.declaredWidth())
                : Math.min(maxWidth, snapshot.width());
        int preferredHeight = allowUpscale
                ? maxHeight
                : image.declaredHeight() > 0
                ? Math.min(maxHeight, image.declaredHeight())
                : Math.min(maxHeight, snapshot.height());

        int drawWidth = Math.max(1, preferredWidth);
        int drawHeight = (int) Math.round((double) drawWidth * snapshot.height() / snapshot.width());
        if (drawHeight > preferredHeight) {
            drawHeight = Math.max(1, preferredHeight);
            drawWidth = (int) Math.round((double) drawHeight * snapshot.width() / snapshot.height());
        }
        drawWidth = Math.max(1, drawWidth);
        drawHeight = Math.max(1, drawHeight);

        int drawX = x + (maxWidth - drawWidth) / 2;
        int drawY = y + (maxHeight - drawHeight) / 2;
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                snapshot.textureId(),
                drawX,
                drawY,
                0.0F,
                0.0F,
                drawWidth,
                drawHeight,
                snapshot.width(),
                snapshot.height(),
                snapshot.width(),
                snapshot.height()
        );
        return true;
    }

    protected void drawLicenseProtectionImageMessage(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        if (width < 70 || height < LINE_HEIGHT * 3) {
            int compactWidth = Math.max(1, width - 4);
            String compactMessage = font.width("Open source") <= compactWidth
                    ? "Open source"
                    : font.width("Source") <= compactWidth ? "Source" : "Src";
            graphics.centeredText(font, compactMessage, x + width / 2, y + height / 2 - 4, LINK);
            return;
        }

        int textWidth = Math.max(8, width - 8);
        String protectedText = width < 110
                ? "License protected"
                : "This image is protected by its license.";
        String sourceText = width < 110
                ? "Open source"
                : "Click to open the source.";
        List<FormattedCharSequence> protectedLines = font.split(Component.literal(protectedText), textWidth);
        List<FormattedCharSequence> sourceLines = font.split(Component.literal(sourceText), textWidth);

        int lineCount = protectedLines.size() + sourceLines.size();
        int availableLines = Math.max(1, (height - 6) / LINE_HEIGHT);
        if (lineCount > availableLines) {
            String compactMessage = font.width("Open source") <= textWidth ? "Open source" : "Source";
            graphics.centeredText(font, compactMessage, x + width / 2, y + height / 2 - 4, LINK);
            return;
        }

        int gap = protectedLines.isEmpty() || sourceLines.isEmpty() ? 0 : 2;
        int contentHeight = lineCount * LINE_HEIGHT + gap;
        int lineY = y + Math.max(3, (height - contentHeight) / 2);

        for (FormattedCharSequence line : protectedLines) {
            int lineX = x + Math.max(0, (width - font.width(line)) / 2);
            graphics.text(font, line, lineX, lineY, MUTED, false);
            lineY += LINE_HEIGHT;
        }

        lineY += gap;
        for (FormattedCharSequence line : sourceLines) {
            int lineX = x + Math.max(0, (width - font.width(line)) / 2);
            graphics.text(font, line, lineX, lineY, LINK, false);
            lineY += LINE_HEIGHT;
        }
    }

    protected void drawSlot(GuiGraphicsExtractor graphics, int x, int y, int size, WikiItemSlot slot) {
        graphics.fill(x, y, x + size, y + size, SLOT);
        graphics.fill(x, y, x + size, y + 1, SLOT_BORDER_DARK);
        graphics.fill(x, y, x + 1, y + size, SLOT_BORDER_DARK);
        graphics.fill(x, y + size - 1, x + size, y + size, SLOT_BORDER_LIGHT);
        graphics.fill(x + size - 1, y, x + size, y + size, SLOT_BORDER_LIGHT);
        if (slot.isEmpty()) {
            return;
        }

        WikiItemSlot.Frame frame = displayedFrame(slot);
        boolean hovered = contains(renderMouseX, renderMouseY, x, y, size, size);
        if (hovered) {
            graphics.fill(x, y, x + size, y + 1, LINK);
            graphics.fill(x, y + size - 1, x + size, y + size, LINK);
            graphics.fill(x, y, x + 1, y + size, LINK);
            graphics.fill(x + size - 1, y, x + size, y + size, LINK);
        }
        slotHitboxes.add(new SlotHitbox(x, y, size, size, frame));

        boolean rendered = drawRemoteImage(graphics, frame.image(), x + 2, y + 2, size - 4, size - 4);
        if (!rendered && frame.image().isEmpty() && !frame.displayName().isBlank()) {
            String shortName = font.plainSubstrByWidth(frame.displayName(), size - 4);
            graphics.centeredText(font, shortName, x + size / 2, y + size / 2 - 4, MUTED);
        }

        if (!frame.stackSize().isBlank()) {
            String amount = frame.stackSize();
            graphics.text(font, amount, x + size - font.width(amount) - 2, y + size - 10, TEXT, true);
        }
    }


    protected void renderBorder(GuiGraphicsExtractor graphics, RenderEntry entry, int y, int color) {
        graphics.fill(entry.x(), y, entry.x() + entry.width(), y + 1, color);
        graphics.fill(entry.x(), y + entry.height() - 1, entry.x() + entry.width(), y + entry.height(), color);
        graphics.fill(entry.x(), y, entry.x() + 1, y + entry.height(), color);
        graphics.fill(entry.x() + entry.width() - 1, y, entry.x() + entry.width(), y + entry.height(), color);
    }

    protected void drawCenteredCells(GuiGraphicsExtractor graphics, RenderEntry entry, int y, int color) {
        for (Cell cell : entry.cells()) {
            int lineY = y + cell.yOffset();
            for (FormattedCharSequence line : cell.lines()) {
                int lineX = entry.x() + cell.xOffset()
                        + Math.max(0, (cell.width() - Math.round(font.width(line) * ARTICLE_TEXT_SCALE)) / 2);
                drawInteractiveLine(graphics, line, lineX, lineY, color);
                lineY += LINE_HEIGHT;
            }
        }
    }

    protected void drawCells(GuiGraphicsExtractor graphics, RenderEntry entry, int y, int color) {
        for (Cell cell : entry.cells()) {
            int lineY = y + cell.yOffset();
            for (FormattedCharSequence line : cell.lines()) {
                drawInteractiveLine(graphics, line, entry.x() + cell.xOffset(), lineY, color);
                lineY += LINE_HEIGHT;
            }
        }
    }

    protected void drawInteractiveLine(
            GuiGraphicsExtractor graphics,
            FormattedCharSequence line,
            int x,
            int y,
            int color
    ) {
        drawFindHighlights(graphics, line, x, y);

        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(ARTICLE_TEXT_SCALE, ARTICLE_TEXT_SCALE);
        graphics.text(font, line, 0, 0, color, true);
        graphics.pose().popMatrix();

        final int[] logicalCursor = {0};
        final int[] linkSegmentStart = {x};
        final int[] hoverSegmentStart = {x};
        final int[] inlineSegmentStart = {x};
        final URI[] activeUri = {null};
        final Component[] activeTooltip = {null};
        final String[] activeInlineImage = {null};

        line.accept((index, style, codePoint) -> {
            int cursorX = x + Math.round(logicalCursor[0] * ARTICLE_TEXT_SCALE);

            String inlineImageId = inlineImageId(style);
            if (!Objects.equals(inlineImageId, activeInlineImage[0])) {
                if (activeInlineImage[0] != null && cursorX > inlineSegmentStart[0]) {
                    drawInlineImage(
                            graphics,
                            inlineImages.get(activeInlineImage[0]),
                            inlineSegmentStart[0],
                            y,
                            cursorX - inlineSegmentStart[0],
                            INLINE_IMAGE_SIZE
                    );
                }
                activeInlineImage[0] = inlineImageId;
                inlineSegmentStart[0] = cursorX;
            }

            URI uri = clickUri(style);
            if (!Objects.equals(uri, activeUri[0])) {
                if (activeUri[0] != null && cursorX > linkSegmentStart[0]) {
                    linkHitboxes.add(new LinkHitbox(
                            linkSegmentStart[0],
                            y,
                            cursorX - linkSegmentStart[0],
                            LINE_HEIGHT,
                            activeUri[0]
                    ));
                }
                activeUri[0] = uri;
                linkSegmentStart[0] = cursorX;
            }

            Component tooltip = hoverText(style);
            if (!Objects.equals(tooltip, activeTooltip[0])) {
                if (activeTooltip[0] != null && cursorX > hoverSegmentStart[0]) {
                    textHoverHitboxes.add(new TextHoverHitbox(
                            hoverSegmentStart[0],
                            y,
                            cursorX - hoverSegmentStart[0],
                            LINE_HEIGHT,
                            activeTooltip[0]
                    ));
                }
                activeTooltip[0] = tooltip;
                hoverSegmentStart[0] = cursorX;
            }

            logicalCursor[0] += font.width(FormattedCharSequence.codepoint(codePoint, style));
            return true;
        });

        int cursorX = x + Math.round(logicalCursor[0] * ARTICLE_TEXT_SCALE);
        if (activeInlineImage[0] != null && cursorX > inlineSegmentStart[0]) {
            drawInlineImage(
                    graphics,
                    inlineImages.get(activeInlineImage[0]),
                    inlineSegmentStart[0],
                    y,
                    cursorX - inlineSegmentStart[0],
                    INLINE_IMAGE_SIZE
            );
        }

        if (activeUri[0] != null && cursorX > linkSegmentStart[0]) {
            linkHitboxes.add(new LinkHitbox(
                    linkSegmentStart[0],
                    y,
                    cursorX - linkSegmentStart[0],
                    LINE_HEIGHT,
                    activeUri[0]
            ));
        }
        if (activeTooltip[0] != null && cursorX > hoverSegmentStart[0]) {
            textHoverHitboxes.add(new TextHoverHitbox(
                    hoverSegmentStart[0],
                    y,
                    cursorX - hoverSegmentStart[0],
                    LINE_HEIGHT,
                    activeTooltip[0]
            ));
        }
    }

    protected void drawFindHighlights(GuiGraphicsExtractor graphics, FormattedCharSequence line, int x, int y) {
        if (!findBarVisible || findQuery.isBlank()) return;

        StringBuilder plainText = new StringBuilder();
        List<Integer> charOffsets = new ArrayList<>();
        List<Integer> logicalWidths = new ArrayList<>();
        charOffsets.add(0);
        logicalWidths.add(0);

        final int[] logicalCursor = {0};
        line.accept((index, style, codePoint) -> {
            plainText.appendCodePoint(codePoint);
            logicalCursor[0] += font.width(FormattedCharSequence.codepoint(codePoint, style));
            charOffsets.add(plainText.length());
            logicalWidths.add(logicalCursor[0]);
            return true;
        });

        String haystack = plainText.toString().toLowerCase(Locale.ROOT);
        String needle = findQuery.toLowerCase(Locale.ROOT);
        int matchStart = haystack.indexOf(needle);
        while (matchStart >= 0) {
            int matchEnd = matchStart + needle.length();
            int startWidth = logicalWidthAt(charOffsets, logicalWidths, matchStart);
            int endWidth = logicalWidthAt(charOffsets, logicalWidths, matchEnd);
            int left = x + Math.round(startWidth * ARTICLE_TEXT_SCALE);
            int right = x + Math.round(endWidth * ARTICLE_TEXT_SCALE);
            if (right > left) {
                int highlightColor = findHighlightColor(left, y, right - left);
                if (highlightColor != 0) {
                    graphics.fill(left, y, right, y + LINE_HEIGHT - 1, highlightColor);
                }
            }
            matchStart = haystack.indexOf(needle, matchStart + Math.max(1, needle.length()));
        }
    }

    protected void drawCenteredFindHighlights(GuiGraphicsExtractor graphics, String text, int centerX, int y) {
        if (!findBarVisible || findQuery.isBlank() || text == null || text.isEmpty()) return;

        String haystack = text.toString().toLowerCase(Locale.ROOT);
        String needle = findQuery.toLowerCase(Locale.ROOT);
        int textX = centerX - font.width(text) / 2;
        int matchStart = haystack.indexOf(needle);
        while (matchStart >= 0) {
            int matchEnd = matchStart + needle.length();
            int left = textX + font.width(text.substring(0, matchStart));
            int right = textX + font.width(text.substring(0, matchEnd));
            if (right > left) {
                int highlightColor = findHighlightColor(left, y, right - left);
                if (highlightColor != 0) {
                    graphics.fill(left, y, right, y + LINE_HEIGHT - 1, highlightColor);
                }
            }
            matchStart = haystack.indexOf(needle, matchStart + Math.max(1, needle.length()));
        }
    }

    private int findHighlightColor(int x, int y, int width) {
        int documentTop = HEADER_HEIGHT + BROWSER_TAB_HEIGHT + TOOLBAR_HEIGHT + 7;
        int inactiveColor = 0;

        for (int index = 0; index < findMatches.size(); index++) {
            FindTarget target = findMatches.get(index);
            int targetY = documentTop + target.y() - scrollPixels;
            boolean insideTarget = x + width > target.x()
                    && x < target.x() + target.width()
                    && y + LINE_HEIGHT > targetY
                    && y < targetY + target.height();
            if (!insideTarget) continue;
            if (index == activeFindIndex) return 0x55FFD83D;
            inactiveColor = 0x337A6A1E;
        }
        return inactiveColor;
    }

    private static int logicalWidthAt(List<Integer> charOffsets, List<Integer> logicalWidths, int charIndex) {
        for (int index = 0; index < charOffsets.size(); index++) {
            int offset = charOffsets.get(index);
            if (offset == charIndex) return logicalWidths.get(index);
            if (offset > charIndex) return logicalWidths.get(Math.max(0, index - 1));
        }
        return logicalWidths.isEmpty() ? 0 : logicalWidths.get(logicalWidths.size() - 1);
    }

    protected static URI clickUri(Style style) {
        if (style == null) {
            return null;
        }
        ClickEvent event = style.getClickEvent();
        return event instanceof ClickEvent.OpenUrl openUrl ? openUrl.uri() : null;
    }

    protected static Component hoverText(Style style) {
        if (style == null) {
            return null;
        }
        HoverEvent event = style.getHoverEvent();
        return event instanceof HoverEvent.ShowText showText ? showText.value() : null;
    }

    protected static String inlineImageId(Style style) {
        if (style == null || style.getInsertion() == null) return null;
        String insertion = style.getInsertion();
        return insertion.startsWith(INLINE_IMAGE_INSERTION_PREFIX)
                ? insertion.substring(INLINE_IMAGE_INSERTION_PREFIX.length())
                : null;
    }

    protected void drawInlineImage(
            GuiGraphicsExtractor graphics,
            WikiImage image,
            int x,
            int y,
            int reservedWidth,
            int size
    ) {
        if (image == null || image.isEmpty() || reservedWidth <= 0 || size <= 0) {
            return;
        }
        WikiImageTextureCache.Snapshot snapshot = WikiImageTextureCache.request(image);
        if (!snapshot.ready()) return;

        // Item PNGs frequently have a large transparent canvas around the
        // visible sprite. Lay out and sample the visible alpha bounds rather
        // than the full canvas; otherwise narrow sprites appear to have a
        // mysterious gap even when their text slot is correctly positioned.
        int sourceX = snapshot.contentWidth() > 0 ? snapshot.contentX() : 0;
        int sourceY = snapshot.contentHeight() > 0 ? snapshot.contentY() : 0;
        int sourceWidth = snapshot.contentWidth() > 0 ? snapshot.contentWidth() : snapshot.width();
        int sourceHeight = snapshot.contentHeight() > 0 ? snapshot.contentHeight() : snapshot.height();

        int availableHeight = Math.max(1, Math.min(size, Math.round(font.lineHeight * ARTICLE_TEXT_SCALE)));
        int availableWidth = Math.max(1, reservedWidth - 1);
        int drawWidth = Math.min(size, availableWidth);
        int drawHeight = Math.max(1, (int) Math.round(
                (double) drawWidth * sourceHeight / sourceWidth
        ));
        if (drawHeight > availableHeight) {
            drawHeight = availableHeight;
            drawWidth = Math.max(1, (int) Math.round(
                    (double) drawHeight * sourceWidth / sourceHeight
            ));
        }

        int drawX = x + Math.max(0, (reservedWidth - drawWidth) / 2);
        int drawY = y + Math.max(0, (availableHeight - drawHeight) / 2);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                snapshot.textureId(),
                drawX,
                drawY,
                (float) sourceX,
                (float) sourceY,
                drawWidth,
                drawHeight,
                sourceWidth,
                sourceHeight,
                snapshot.width(),
                snapshot.height()
        );
    }

    protected static List<WikiItemSlot> castSlots(Object value) {
        return value instanceof List<?> list ? (List<WikiItemSlot>) list : List.of();
    }

}
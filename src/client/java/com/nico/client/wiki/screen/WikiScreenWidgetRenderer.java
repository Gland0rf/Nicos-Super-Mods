package com.nico.client.wiki.screen;

import static com.nico.client.wiki.screen.WikiScreenMetrics.*;

import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiContent;
import com.nico.client.wiki.WikiCraftingGrid;
import com.nico.client.wiki.WikiImage;
import com.nico.client.wiki.WikiImageTextureCache;
import com.nico.client.wiki.WikiInfobox;
import com.nico.client.wiki.WikiItemSlot;
import com.nico.client.wiki.WikiText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.util.List;
import java.util.Objects;

abstract class WikiScreenWidgetRenderer extends WikiScreenRenderer {
    protected WikiScreenWidgetRenderer(Screen parent, ItemStack itemStack) {
        super(parent, itemStack);
    }

    protected void renderEntry(GuiGraphics graphics, RenderEntry entry, int y) {
        switch (entry.kind()) {
            case PAGE_TITLE -> {
                drawCells(graphics, entry, y, TEXT);
                graphics.fill(entry.x(), y + entry.height() - 1, entry.x() + entry.width(), y + entry.height(), DIVIDER);
            }
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
                drawCells(graphics, entry, y, TEXT);
            }
            case INFOBOX_IMAGE -> renderInfoboxImage(graphics, entry, y);
            case INFOBOX_SLOTS -> renderSlotStrip(graphics, entry, y,
                    ((WikiInfobox.SlotStrip) entry.payload()).slots());
            case INFOBOX_TABS -> renderInfoboxTabs(graphics, entry, y);
            case INFOBOX_HEADER -> {
                graphics.fill(entry.x(), y, entry.x() + entry.width(), y + entry.height(), BLUE_DARK);
                drawCells(graphics, entry, y, TEXT);
            }
            case INFOBOX_ROW -> renderInfoboxRow(graphics, entry, y);
            case SLOT_STRIP -> renderSlotStrip(graphics, entry, y, castSlots(entry.payload()));
            case IMAGE -> renderWikiImageEntry(graphics, entry, y);
            case TABS -> renderTabs(graphics, entry, y);
            case TAB_BORDER -> renderBorder(graphics, entry, y, BORDER);
            case CRAFTING -> renderCrafting(graphics, entry, y, (WikiCraftingGrid) entry.payload());
        }
    }

    protected void renderTable(GuiGraphics graphics, RenderEntry entry, int y) {
        if (!(entry.payload() instanceof TableLayout table)) {
            return;
        }

        int rowY = y;
        boolean alternate = false;
        for (int row = 0; row < table.rowHeights().length; row++) {
            boolean header = table.headerRows()[row];
            int background = header ? tableHeadColor() : (alternate ? tableAltColor() : tableRowColor());
            graphics.fill(entry.x(), rowY, entry.x() + entry.width(), rowY + table.rowHeights()[row], background);
            if (!header) {
                alternate = !alternate;
            }
            rowY += table.rowHeights()[row];
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

            int contentX = cellX + 5;
            int contentY = cellY + 4;
            int innerWidth = Math.max(12, cell.width() - 10);
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
                int imageHeight = imageBoxHeight(image, innerWidth, 18, 46);
                drawRemoteImage(graphics, image, contentX, contentY, innerWidth, imageHeight);
                contentY += imageHeight + 3;
            }
        }
    }

    protected void renderTableRow(GuiGraphics graphics, RenderEntry entry, int y) {
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
                    int imageHeight = imageBoxHeight(image, cell.width(), 18, 46);
                    drawRemoteImage(
                            graphics,
                            image,
                            entry.x() + cell.xOffset(),
                            contentY,
                            cell.width(),
                            imageHeight
                    );
                    contentY += imageHeight + 3;
                }
            }
        }

        graphics.fill(entry.x(), y + entry.height() - 1, entry.x() + entry.width(), y + entry.height(), BORDER);
    }

    protected int renderCompactSlots(
            GuiGraphics graphics,
            List<WikiItemSlot> slots,
            int x,
            int y,
            int width
    ) {
        if (slots == null || slots.isEmpty()) {
            return y;
        }

        int step = compactSlotStep(width);
        int slotSize = step - 2;
        int perRow = Math.max(1, width / step);
        for (int index = 0; index < slots.size(); index++) {
            int slotX = x + (index % perRow) * step;
            int slotY = y + (index / perRow) * step;
            drawSlot(graphics, slotX, slotY, slotSize, slots.get(index));
        }

        int rows = (slots.size() + perRow - 1) / perRow;
        return y + rows * step + 3;
    }

    protected int renderCompactCrafting(
            GuiGraphics graphics,
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
        graphics.drawString(font, "->", arrowX, arrowY, MUTED, false);
        drawSlot(graphics, arrowX + 19, y + step - 3, outputSize, grid.output());
        return y + compactCraftingHeight(width) + 3;
    }

    protected void renderInfoboxImage(GuiGraphics graphics, RenderEntry entry, int y) {
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
            graphics.renderItem(itemStack, entry.x() + entry.width() / 2 - 8, y + 18);
        }
        if (!image.caption().isBlank()) {
            String caption = font.plainSubstrByWidth(image.caption().plainText(), entry.width() - 12);
            graphics.drawCenteredString(font, caption, entry.x() + entry.width() / 2,
                    y + entry.height() - captionHeight + 2, MUTED);
        }
        renderBorder(graphics, entry, y, BORDER);
    }

    protected void renderInfoboxTabs(GuiGraphics graphics, RenderEntry entry, int y) {
        WikiInfobox.PanelTabs tabs = (WikiInfobox.PanelTabs) entry.payload();
        if (tabs.labels().isEmpty()) {
            return;
        }
        int tabWidth = Math.max(1, entry.width() / tabs.labels().size());
        for (int i = 0; i < tabs.labels().size(); i++) {
            int x = entry.x() + i * tabWidth;
            int right = i == tabs.labels().size() - 1 ? entry.x() + entry.width() : x + tabWidth;
            graphics.fill(x, y, right, y + entry.height(), i == entry.aux() ? BLUE : TAB);
            graphics.drawCenteredString(font, font.plainSubstrByWidth(tabs.labels().get(i), right - x - 6),
                    (x + right) / 2, y + 6, TEXT);
        }
    }

    protected void renderInfoboxRow(GuiGraphics graphics, RenderEntry entry, int y) {
        int split = entry.aux();
        graphics.fill(entry.x(), y, entry.x() + split, y + entry.height(), TABLE_ROW);
        graphics.fill(entry.x() + split, y, entry.x() + entry.width(), y + entry.height(), PAGE);
        graphics.fill(entry.x() + split, y, entry.x() + split + 1, y + entry.height(), BORDER);
        graphics.fill(entry.x(), y + entry.height() - 1, entry.x() + entry.width(), y + entry.height(), BORDER);
        drawCells(graphics, entry, y, TEXT);
    }

    protected void renderSlotStrip(GuiGraphics graphics, RenderEntry entry, int y, List<WikiItemSlot> slots) {
        graphics.fill(entry.x(), y, entry.x() + entry.width(), y + entry.height(), PAGE);
        int perRow = Math.max(1, Math.min(9, (entry.width() - 8) / 22));
        for (int i = 0; i < slots.size(); i++) {
            int sx = entry.x() + 4 + (i % perRow) * 22;
            int sy = y + 4 + (i / perRow) * 23;
            drawSlot(graphics, sx, sy, 20, slots.get(i));
        }
    }

    protected void renderTabs(GuiGraphics graphics, RenderEntry entry, int y) {
        TabPayload payload = (TabPayload) entry.payload();
        for (TabButton button : payload.buttons()) {
            int buttonY = y + button.y() - entry.y();
            boolean active = button.index() == payload.selected();
            graphics.fill(button.x(), buttonY, button.x() + button.width(), buttonY + button.height(), active ? TAB_ACTIVE : TAB);
            renderBorder(graphics, new RenderEntry(Kind.HR, button.x(), buttonY, button.width(), button.height(), List.of(), 0, null),
                    buttonY, BORDER);
            graphics.drawCenteredString(font, font.plainSubstrByWidth(button.title(), button.width() - 8),
                    button.x() + button.width() / 2, buttonY + 5, TEXT);
            tabHitboxes.add(new TabHitbox(button.x(), buttonY, button.width(), button.height(), entry.aux(), button.index()));
        }
    }

    protected void renderCrafting(GuiGraphics graphics, RenderEntry entry, int y, WikiCraftingGrid grid) {
        int left = entry.x();
        int right = entry.x() + entry.width();
        int bottom = y + entry.height();

        graphics.fill(left, y, right, bottom, CRAFTING_BACKGROUND);
        graphics.fill(left, y, right, y + 2, CRAFTING_BORDER_LIGHT);
        graphics.fill(left, y, left + 2, bottom, CRAFTING_BORDER_LIGHT);
        graphics.fill(left, bottom - 2, right, bottom, CRAFTING_BORDER_DARK);
        graphics.fill(right - 2, y, right, bottom, CRAFTING_BORDER_DARK);

        graphics.drawString(font, grid.shapeless() ? "Shapeless Crafting" : "Crafting Recipe",
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

    protected void renderWikiImageEntry(GuiGraphics graphics, RenderEntry entry, int y) {
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
            graphics.drawCenteredString(font, visible, entry.x() + entry.width() / 2,
                    y + entry.height() - captionHeight + 1, MUTED);
        }

        renderBorder(graphics, entry, y, BORDER);
    }

    protected boolean drawRemoteImage(
            GuiGraphics graphics,
            WikiImage image,
            int x,
            int y,
            int maxWidth,
            int maxHeight
    ) {
        if (image == null || image.isEmpty() || maxWidth <= 0 || maxHeight <= 0) {
            return false;
        }

        WikiImageTextureCache.Snapshot snapshot = WikiImageTextureCache.request(image);
        if (!snapshot.ready()) {
            String message = snapshot.status() == WikiImageTextureCache.Status.FAILED
                    ? (snapshot.error().isBlank() ? "Image failed" : snapshot.error())
                    : "Loading image...";
            if (message == null || message.isBlank()) {
                message = "Wiki image";
            }
            graphics.drawCenteredString(font, font.plainSubstrByWidth(message, Math.max(8, maxWidth - 4)),
                    x + maxWidth / 2, y + maxHeight / 2 - 4, MUTED);
            return false;
        }

        int preferredWidth = image.declaredWidth() > 0
                ? Math.min(maxWidth, image.declaredWidth())
                : Math.min(maxWidth, snapshot.width());
        int preferredHeight = image.declaredHeight() > 0
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

    protected void drawSlot(GuiGraphics graphics, int x, int y, int size, WikiItemSlot slot) {
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
            graphics.drawCenteredString(font, shortName, x + size / 2, y + size / 2 - 4, MUTED);
        }

        if (!frame.stackSize().isBlank()) {
            String amount = frame.stackSize();
            graphics.drawString(font, amount, x + size - font.width(amount) - 2, y + size - 10, TEXT, true);
        }
    }


    protected void renderBorder(GuiGraphics graphics, RenderEntry entry, int y, int color) {
        graphics.fill(entry.x(), y, entry.x() + entry.width(), y + 1, color);
        graphics.fill(entry.x(), y + entry.height() - 1, entry.x() + entry.width(), y + entry.height(), color);
        graphics.fill(entry.x(), y, entry.x() + 1, y + entry.height(), color);
        graphics.fill(entry.x() + entry.width() - 1, y, entry.x() + entry.width(), y + entry.height(), color);
    }

    protected void drawCells(GuiGraphics graphics, RenderEntry entry, int y, int color) {
        for (Cell cell : entry.cells()) {
            int lineY = y + cell.yOffset();
            for (FormattedCharSequence line : cell.lines()) {
                drawInteractiveLine(graphics, line, entry.x() + cell.xOffset(), lineY, color);
                lineY += LINE_HEIGHT;
            }
        }
    }

    protected void drawInteractiveLine(
            GuiGraphics graphics,
            FormattedCharSequence line,
            int x,
            int y,
            int color
    ) {
        graphics.drawString(font, line, x, y, color, true);

        final int[] cursorX = {x};
        final int[] segmentStart = {x};
        final URI[] activeUri = {null};

        line.accept((index, style, codePoint) -> {
            URI uri = clickUri(style);
            if (!Objects.equals(uri, activeUri[0])) {
                if (activeUri[0] != null && cursorX[0] > segmentStart[0]) {
                    linkHitboxes.add(new LinkHitbox(
                            segmentStart[0],
                            y,
                            cursorX[0] - segmentStart[0],
                            LINE_HEIGHT,
                            activeUri[0]
                    ));
                }
                activeUri[0] = uri;
                segmentStart[0] = cursorX[0];
            }

            cursorX[0] += Math.max(0, font.width(FormattedCharSequence.codepoint(codePoint, style)));
            return true;
        });

        if (activeUri[0] != null && cursorX[0] > segmentStart[0]) {
            linkHitboxes.add(new LinkHitbox(
                    segmentStart[0],
                    y,
                    cursorX[0] - segmentStart[0],
                    LINE_HEIGHT,
                    activeUri[0]
            ));
        }
    }

    protected static URI clickUri(Style style) {
        if (style == null) {
            return null;
        }
        ClickEvent event = style.getClickEvent();
        return event instanceof ClickEvent.OpenUrl openUrl ? openUrl.uri() : null;
    }

    protected static List<WikiItemSlot> castSlots(Object value) {
        return value instanceof List<?> list ? (List<WikiItemSlot>) list : List.of();
    }

}
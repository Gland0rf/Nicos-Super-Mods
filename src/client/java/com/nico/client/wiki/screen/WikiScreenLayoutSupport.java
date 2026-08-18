package com.nico.client.wiki.screen;

import com.nico.client.wiki.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.nico.client.wiki.screen.WikiScreenMetrics.*;
import static com.nico.client.wiki.screen.WikiScreenMetrics.compactCraftingHeight;
import static com.nico.client.wiki.screen.WikiScreenMetrics.tableImageHeight;

/**
 * Reusable layout primitives shared by the document-level screen layout.
 *
 * <p>This class contains the lower-level routines for laying out rich content, tables, crafting grids,
 * searchable text, and styled Minecraft components. Keeping these details separate leaves
 * {@link WikiScreenLayout} focused on page flow: title, table of contents, infobox, and block order.</p>
 */
abstract class WikiScreenLayoutSupport extends WikiScreenActions {
    protected WikiScreenLayoutSupport(Screen parent, ItemStack itemStack) {
        super(parent, itemStack);
    }

    // Rich content and media

    protected int layoutContent(WikiContent content, int x, int y, int width, boolean compact) {
        if (!content.text().isBlank()) {
            List<FormattedCharSequence> lines = splitWikiText(toComponent(content.text()), width);
            int h = Math.max(LINE_HEIGHT, lines.size() * LINE_HEIGHT);
            entries.add(new RenderEntry(Kind.TEXT, x, y, width, h,
                    List.of(new Cell(0, 0, width, lines)), 0, null));
            y += h + (compact ? 3 : 7);
        }
        if (!content.itemSlots().isEmpty()) {
            int h = Math.max(27, ((content.itemSlots().size() + 8) / 9) * 23 + 4);
            entries.add(new RenderEntry(Kind.SLOT_STRIP, x, y, width, h, List.of(), 0, content.itemSlots()));
            y += h + 5;
        }
        WikiCraftingGrid activeGrid = activeCraftingGrid(content.craftingGrids());
        if (activeGrid != null) {
            y = layoutCrafting(activeGrid, x, y, width);
        }
        for (WikiImage image : content.images()) {
            int imageWidth = preferredImageWidth(image, width, 260);
            int h = imageBoxHeight(image, imageWidth - 8, 32, 140) + 8;
            entries.add(new RenderEntry(Kind.IMAGE, x, y, imageWidth, h, List.of(), 0, image));
            y += h + 6;
        }
        return y;
    }

    protected static long animationStep() {
        return System.currentTimeMillis() / ANIMATION_PERIOD_MILLIS;
    }

    protected static int animationIndex(int size) {
        return size <= 1 ? 0 : (int) Math.floorMod(animationStep(), size);
    }

    protected static WikiCraftingGrid activeCraftingGrid(List<WikiCraftingGrid> grids) {
        if (grids == null || grids.isEmpty()) {
            return null;
        }
        return grids.get(animationIndex(grids.size()));
    }

    protected static WikiItemSlot.Frame displayedFrame(WikiItemSlot slot) {
        if (slot == null || slot.frames().isEmpty()) {
            return WikiItemSlot.Frame.empty();
        }
        int index = Math.floorMod(slot.activeFrameIndex() + animationIndex(slot.frames().size()), slot.frames().size());
        return slot.frames().get(index);
    }

    protected static int preferredImageWidth(WikiImage image, int availableWidth, int maximumWidth) {
        int cap = Math.max(1, Math.min(availableWidth, maximumWidth));
        if (image == null || image.isEmpty() || image.declaredWidth() <= 0) {
            return cap;
        }
        return Math.max(16, Math.min(cap, image.declaredWidth()));
    }

    protected static int imageBoxHeight(WikiImage image, int maxWidth, int minimum, int maximum) {
        if (image == null || image.isEmpty()) {
            return minimum;
        }
        int sourceWidth = image.declaredWidth();
        int sourceHeight = image.declaredHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return Math.min(maximum, Math.max(minimum, maxWidth * 3 / 5));
        }
        int scaled = (int) Math.round((double) Math.max(1, maxWidth) * sourceHeight / sourceWidth);
        return Math.max(minimum, Math.min(maximum, scaled));
    }

    // Tables

    protected int layoutTable(WikiBlock.Table table, int x, int y, int width) {
        int rowCount = table.rows().size();
        int columns = Math.max(1, Math.min(8, table.columnCount()));
        if (rowCount == 0) {
            return y;
        }

        int[] widths = columnWidthsForTable(table, width, columns);
        boolean[][] occupied = new boolean[rowCount][columns];
        List<TableCellLayout> placedCells = new ArrayList<>();
        boolean[] headerRows = new boolean[rowCount];

        for (int rowIndex = 0; rowIndex < rowCount; rowIndex++) {
            WikiBlock.Table.Row row = table.rows().get(rowIndex);
            boolean allHeader = !row.cells().isEmpty();
            int searchColumn = 0;

            for (WikiBlock.Table.Cell source : row.cells()) {
                while (searchColumn < columns && occupied[rowIndex][searchColumn]) {
                    searchColumn++;
                }
                if (searchColumn >= columns) {
                    break;
                }

                int columnSpan = Math.max(1, Math.min(source.columnSpan(), columns - searchColumn));
                int rowSpan = Math.max(1, Math.min(source.rowSpan(), rowCount - rowIndex));
                int cellWidth = sum(widths, searchColumn, searchColumn + columnSpan);
                int innerWidth = Math.max(20, cellWidth - 14);
                List<FormattedCharSequence> lines = splitWikiText(toComponent(source.content().text()), innerWidth);
                int contentHeight = tableCellContentHeight(source.content(), innerWidth, lines.size());

                placedCells.add(new TableCellLayout(
                        rowIndex,
                        searchColumn,
                        rowSpan,
                        columnSpan,
                        source.header(),
                        lines,
                        source.content(),
                        contentHeight
                ));

                for (int coveredRow = rowIndex; coveredRow < rowIndex + rowSpan; coveredRow++) {
                    for (int coveredColumn = searchColumn;
                         coveredColumn < searchColumn + columnSpan;
                         coveredColumn++) {
                        occupied[coveredRow][coveredColumn] = true;
                    }
                }

                allHeader &= source.header();
                searchColumn += columnSpan;
            }
            headerRows[rowIndex] = allHeader;
        }

        int[] rowHeights = new int[rowCount];
        java.util.Arrays.fill(rowHeights, 22);

        for (TableCellLayout cell : placedCells) {
            if (cell.rowSpan() == 1) {
                rowHeights[cell.row()] = Math.max(rowHeights[cell.row()], cell.contentHeight() + 12);
            }
        }

        for (TableCellLayout cell : placedCells) {
            if (cell.rowSpan() <= 1) {
                continue;
            }
            int currentHeight = sum(rowHeights, cell.row(), cell.row() + cell.rowSpan());
            int requiredHeight = cell.contentHeight() + 12;
            if (currentHeight < requiredHeight) {
                rowHeights[cell.row() + cell.rowSpan() - 1] += requiredHeight - currentHeight;
            }
        }

        int[] rowTops = new int[rowCount + 1];
        for (int row = 0; row < rowCount; row++) {
            rowTops[row + 1] = rowTops[row] + rowHeights[row];
        }

        List<RenderedTableCell> renderedCells = new ArrayList<>(placedCells.size());
        for (TableCellLayout cell : placedCells) {
            renderedCells.add(new RenderedTableCell(
                    sum(widths, 0, cell.column()),
                    rowTops[cell.row()],
                    sum(widths, cell.column(), cell.column() + cell.columnSpan()),
                    rowTops[cell.row() + cell.rowSpan()] - rowTops[cell.row()],
                    cell.header(),
                    cell.lines(),
                    cell.content(),
                    cell.contentHeight()
            ));
        }

        int totalHeight = rowTops[rowCount];
        int tableWidth = sum(widths, 0, widths.length);

        entries.add(new RenderEntry(
                Kind.TABLE,
                x,
                y,
                tableWidth,
                totalHeight,
                List.of(),
                0,
                new TableLayout(rowHeights, headerRows, renderedCells)
        ));
        return y + totalHeight + 9;
    }

    protected int tableCellContentHeight(WikiContent content, int width, int textLineCount) {
        int height = textLineCount * LINE_HEIGHT;
        boolean hasPrevious = textLineCount > 0;

        if (!content.itemSlots().isEmpty()) {
            int step = compactSlotStep(width, content.itemSlots().size());
            int perRow = Math.max(1, width / step);
            int rows = (content.itemSlots().size() + perRow - 1) / perRow;
            height += (hasPrevious ? 3 : 0) + rows * step;
            hasPrevious = true;
        }

        if (!content.craftingGrids().isEmpty()) {
            height += (hasPrevious ? 3 : 0) + compactCraftingHeight(width);
            hasPrevious = true;
        }

        for (WikiImage image : content.images()) {
            height += (hasPrevious ? 3 : 0) + tableImageHeight(content, image, width);
            hasPrevious = true;
        }

        return Math.max(LINE_HEIGHT, height);
    }

    // Find-in-page text extraction

    protected static void appendForgingSearchText(
            List<WikiForgingTree.Node> nodes,
            StringBuilder result
    ) {
        for (WikiForgingTree.Node node : nodes) {
            String text = searchableContent(node.content());
            if (!text.isBlank()) {
                if (!result.isEmpty()) {
                    result.append(' ');
                }
                result.append(text);
            }
            appendForgingSearchText(node.children(), result);
        }
    }

    protected static String searchableText(WikiBlock block) {
        if (block instanceof WikiBlock.Heading heading) {
            return heading.text().plainText();
        }
        if (block instanceof WikiBlock.MessageBox messageBox) {
            return searchableContent(messageBox.content());
        }
        if (block instanceof WikiBlock.Paragraph paragraph) {
            return searchableContent(paragraph.content());
        }
        if (block instanceof WikiBlock.ListItem item) {
            return searchableContent(item.content());
        }
        if (block instanceof WikiBlock.ForgingTree forgingTree) {
            StringBuilder result = new StringBuilder();
            appendForgingSearchText(forgingTree.tree().roots(), result);
            return result.toString();
        }
        if (block instanceof WikiBlock.Table table) {
            StringBuilder result = new StringBuilder();
            for (WikiBlock.Table.Row row : table.rows()) {
                for (WikiBlock.Table.Cell cell : row.cells()) {
                    if (!result.isEmpty()) result.append(' ');
                    result.append(searchableContent(cell.content()));
                }
            }
            return result.toString();
        }
        if (block instanceof WikiBlock.TabGroup tabs) {
            StringBuilder result = new StringBuilder();
            for (WikiBlock.TabGroup.Tab tab : tabs.tabs()) {
                if (!result.isEmpty()) result.append(' ');
                result.append(tab.title());
            }
            return result.toString();
        }
        if (block instanceof WikiBlock.UiGroup uiGroup) {
            StringBuilder result = new StringBuilder();
            for (WikiBlock.UiGroup.Panel panel : uiGroup.panels()) {
                for (WikiBlock panelBlock : panel.blocks()) {
                    String textValue = searchableText(panelBlock);
                    if (!textValue.isBlank()) {
                        if (!result.isEmpty()) result.append(' ');
                        result.append(textValue);
                    }
                }
            }
            return result.toString();
        }
        if (block instanceof WikiBlock.Crafting crafting) {
            StringBuilder result = new StringBuilder();
            for (WikiItemSlot slot : crafting.grid().inputs()) {
                if (!slot.isEmpty()) result.append(' ').append(slot.activeFrame().displayName());
            }
            if (!crafting.grid().output().isEmpty()) {
                result.append(' ').append(crafting.grid().output().activeFrame().displayName());
            }
            return result.toString().trim();
        }
        if (block instanceof WikiBlock.Image image) {
            return image.image().displayName() + " " + image.caption().plainText();
        }
        return "";
    }

    protected static String searchableContent(WikiContent content) {
        if (content == null) {
            return "";
        }
        StringBuilder result = new StringBuilder(content.text().plainText());
        for (WikiItemSlot slot : content.itemSlots()) {
            for (WikiItemSlot.Frame frame : slot.frames()) {
                if (!frame.displayName().isBlank()) {
                    if (!result.isEmpty()) result.append(' ');
                    result.append(frame.displayName());
                }
                if (!frame.tooltipText().isBlank()) {
                    if (!result.isEmpty()) result.append(' ');
                    result.append(frame.tooltipText());
                }
            }
        }
        for (WikiCraftingGrid grid : content.craftingGrids()) {
            for (WikiItemSlot slot : grid.inputs()) {
                if (!slot.isEmpty()) {
                    if (!result.isEmpty()) result.append(' ');
                    result.append(slot.activeFrame().displayName());
                }
            }
            if (!grid.output().isEmpty()) {
                if (!result.isEmpty()) result.append(' ');
                result.append(grid.output().activeFrame().displayName());
            }
        }
        for (WikiImage image : content.images()) {
            if (!image.displayName().isBlank()) {
                if (!result.isEmpty()) result.append(' ');
                result.append(image.displayName());
            }
        }
        return result.toString();
    }

    protected static int uiSelectionKey(String groupKey) {
        return -1 - (groupKey == null ? 0 : (groupKey.hashCode() & 0x7fffffff));
    }

    protected int layoutCrafting(WikiCraftingGrid grid, int x, int y, int width) {
        int cardWidth = Math.min(width, 284);
        int h = 111;
        entries.add(new RenderEntry(Kind.CRAFTING, x, y, cardWidth, h, List.of(), 0, grid));
        return y + h + 9;
    }

    // Rich-text measurement and Minecraft styling

    /** Split article text using the same scale that is used at render time. */
    protected List<FormattedCharSequence> splitWikiText(Component component, int width) {
        int logicalWidth = Math.max(1, (int) Math.floor(width / ARTICLE_TEXT_SCALE));
        return font.split(component, logicalWidth);
    }

    protected int wikiTextWidth(Component component) {
        return Math.max(0, Math.round(font.width(component) * ARTICLE_TEXT_SCALE));
    }

    protected static ChatFormatting spanFormatting(WikiText.Span span) {
        if (span.isLink()) {
            return ChatFormatting.AQUA;
        }

        String classes = span.cssClasses().toLowerCase(Locale.ROOT);
        if (containsCssMarker(classes, "very-special")
                || containsCssMarker(classes, "special")
                || containsCssMarker(classes, "negative")
                || containsCssMarker(classes, "error")
                || containsCssMarker(classes, "no")) {
            return ChatFormatting.RED;
        }
        if (containsCssMarker(classes, "mythic")) {
            return ChatFormatting.LIGHT_PURPLE;
        }
        if (containsCssMarker(classes, "legendary")
                || containsCssMarker(classes, "orange")) {
            return ChatFormatting.GOLD;
        }
        if (containsCssMarker(classes, "epic")
                || containsCssMarker(classes, "purple")) {
            return ChatFormatting.DARK_PURPLE;
        }
        if (containsCssMarker(classes, "uncommon")
                || containsCssMarker(classes, "positive")
                || containsCssMarker(classes, "success")
                || containsCssMarker(classes, "yes")
                || containsCssMarker(classes, "green")) {
            return ChatFormatting.GREEN;
        }
        if (containsCssMarker(classes, "divine")
                || containsCssMarker(classes, "aqua")
                || containsCssMarker(classes, "cyan")) {
            return ChatFormatting.AQUA;
        }
        if (containsCssMarker(classes, "rare")
                || containsCssMarker(classes, "blue")) {
            return ChatFormatting.BLUE;
        }
        if (containsCssMarker(classes, "muted")
                || containsCssMarker(classes, "gray")
                || containsCssMarker(classes, "grey")) {
            return ChatFormatting.GRAY;
        }
        if (containsCssMarker(classes, "warning")
                || containsCssMarker(classes, "yellow")) {
            return ChatFormatting.YELLOW;
        }
        return ChatFormatting.WHITE;
    }

    private static boolean containsCssMarker(String classes, String marker) {
        if (classes == null || classes.isBlank() || marker == null || marker.isBlank()) {
            return false;
        }
        for (String token : classes.split("\\s+")) {
            if (token.equals(marker)
                    || token.endsWith("-" + marker)
                    || token.startsWith(marker + "-")) {
                return true;
            }
        }
        return false;
    }

    protected MutableComponent toComponent(WikiText text) {
        MutableComponent result = Component.empty();
        for (WikiText.Span span : text.spans()) {
            MutableComponent part = Component.literal(span.text());
            part.withStyle(spanFormatting(span));
            if (span.hasInlineImage()) {
                String imageId = Integer.toString(nextInlineImageId++);
                inlineImages.put(imageId, span.inlineImage());
                part.withStyle(style -> style.withInsertion(INLINE_IMAGE_INSERTION_PREFIX + imageId));
            }
            if (span.bold()) {
                part.withStyle(ChatFormatting.BOLD);
            }
            if (span.italic()) {
                part.withStyle(ChatFormatting.ITALIC);
            }
            if (span.isLink()) {
                URI uri = resolveHref(span.href());
                if (uri != null) {
                    part.withStyle(style -> style
                            .withClickEvent(new ClickEvent.OpenUrl(uri)));
                }
            }
            if (span.isHoverable()) {
                MutableComponent tooltip = Component.empty();
                if (!span.hoverTitle().isBlank()) {
                    tooltip.append(WikiScreenInteractionRenderer.parseLegacyFormatting(
                            span.hoverTitle(), ChatFormatting.AQUA));
                }
                if (!span.hoverText().isBlank()) {
                    if (!span.hoverTitle().isBlank()) {
                        tooltip.append(Component.literal("\n"));
                    }
                    tooltip.append(WikiScreenInteractionRenderer.parseLegacyFormatting(
                            span.hoverText(), ChatFormatting.GRAY));
                }
                part.withStyle(style -> style.withHoverEvent(new HoverEvent.ShowText(tooltip)));
            }
            result.append(part);
        }
        return result;
    }
}

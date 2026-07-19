package com.nico.client.wiki.screen;

import static com.nico.client.wiki.screen.WikiScreenMetrics.*;

import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiContent;
import com.nico.client.wiki.WikiCraftingGrid;
import com.nico.client.wiki.WikiImage;
import com.nico.client.wiki.WikiInfobox;
import com.nico.client.wiki.WikiItemSlot;
import com.nico.client.wiki.WikiText;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

abstract class WikiScreenLayout extends WikiScreenActions {
    protected WikiScreenLayout(Screen parent, ItemStack itemStack) {
        super(parent, itemStack);
    }

    @Override
    protected void rebuildLayout() {
        entries.clear();
        findTargets.clear();
        tabHitboxes.clear();
        tocHitboxes.clear();
        slotHitboxes.clear();
        nextTabGroup = 0;
        if (page == null) {
            return;
        }

        pageWidth = Math.min(MAX_PAGE_WIDTH, Math.max(280, width - OUTER_MARGIN * 2));
        pageLeft = (width - pageWidth) / 2;

        int desiredInfoboxWidth = Math.min(INFOBOX_WIDTH, Math.max(175, pageWidth * 27 / 100));
        int candidateArticleWidth = pageWidth - PAGE_PADDING * 2 - desiredInfoboxWidth - COLUMN_GAP;
        boolean wide = !page.infobox().isEmpty()
                && candidateArticleWidth >= MIN_ARTICLE_WIDTH
                && !containsWideTable(page.blocks());

        int articleX = pageLeft + PAGE_PADDING;
        int articleWidth = pageWidth - PAGE_PADDING * 2;
        int infoboxX = articleX;
        int infoboxWidth = articleWidth;
        if (wide) {
            infoboxWidth = desiredInfoboxWidth;
            articleWidth -= infoboxWidth + COLUMN_GAP;
            infoboxX = articleX + articleWidth + COLUMN_GAP;
        }

        int articleY = 12;
        articleY = layoutPageTitle(articleX, articleY, articleWidth);
        int infoboxBottom = articleY;
        if (wide) {
            infoboxBottom = layoutInfobox(infoboxX, 12, infoboxWidth);
        } else if (!page.infobox().isEmpty()) {
            articleY = layoutInfobox(articleX, articleY, articleWidth) + 12;
            infoboxBottom = articleY;
        }

        List<TocItem> tocItems = buildToc(page.blocks());
        boolean tocInserted = false;
        int headingIndex = 0;

        for (WikiBlock block : page.blocks()) {
            if (block instanceof WikiBlock.Heading) {
                if (!tocInserted && tocItems.size() >= 2) {
                    articleY = layoutToc(tocItems, articleX, articleY, articleWidth);
                    tocInserted = true;
                }
                if (headingIndex < tocItems.size()) {
                    tocItems.get(headingIndex++).targetY = articleY;
                }
            }
            articleY = layoutBlock(block, articleX, articleY, articleWidth);
        }
        documentHeight = Math.max(articleY, infoboxBottom) + 16;
        updateMaxScroll();
        lastAnimationStep = animationStep();
    }

    protected static boolean containsWideTable(List<WikiBlock> blocks) {
        if (blocks == null) {
            return false;
        }
        for (WikiBlock block : blocks) {
            if (block instanceof WikiBlock.Table table && table.columnCount() >= 4) {
                return true;
            }
            if (block instanceof WikiBlock.TabGroup group) {
                for (WikiBlock.TabGroup.Tab tab : group.tabs()) {
                    if (containsWideTable(tab.blocks())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    protected int layoutPageTitle(int x, int y, int width) {
        MutableComponent title = Component.literal(page.title()).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD);
        List<FormattedCharSequence> titleLines = font.split(title, width);
        MutableComponent source = Component.literal("From " + page.sourceName()).withStyle(ChatFormatting.GRAY);
        int h = titleLines.size() * 13 + 22;
        entries.add(new RenderEntry(Kind.PAGE_TITLE, x, y, width, h, List.of(
                new Cell(0, 0, width, titleLines),
                new Cell(0, titleLines.size() * 13 + 2, width, List.of(source.getVisualOrderText()))
        ), 0, null));
        return y + h + 9;
    }

    protected List<TocItem> buildToc(List<WikiBlock> blocks) {
        List<TocItem> result = new ArrayList<>();
        int section = 0;
        int subsection = 0;
        int subsubsection = 0;

        for (WikiBlock block : blocks) {
            if (!(block instanceof WikiBlock.Heading heading)) {
                continue;
            }

            String number;
            if (heading.level() <= 2) {
                section++;
                subsection = 0;
                subsubsection = 0;
                number = Integer.toString(section);
            } else if (heading.level() == 3) {
                subsection++;
                subsubsection = 0;
                number = section + "." + subsection;
            } else {
                subsubsection++;
                number = section + "." + subsection + "." + subsubsection;
            }

            result.add(new TocItem(
                    heading.level(),
                    number,
                    heading.text().plainText(),
                    heading.anchor()
            ));
        }

        return result;
    }

    protected int layoutToc(List<TocItem> items, int x, int y, int availableWidth) {
        int tocWidth = Math.min(220, availableWidth);
        MutableComponent tocTitle = Component.literal("Contents ")
                .withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD)
                .append(Component.literal("[hide]").withStyle(ChatFormatting.AQUA));
        entries.add(new RenderEntry(
                Kind.TOC_HEADER,
                x,
                y,
                tocWidth,
                29,
                List.of(new Cell(7, 9, tocWidth - 14, List.of(tocTitle.getVisualOrderText()))),
                0,
                null
        ));
        y += 29;

        for (TocItem item : items) {
            int indent = Math.max(0, Math.min(2, item.level - 2)) * 13;
            MutableComponent label = Component.literal(item.number + " " + item.title)
                    .withStyle(ChatFormatting.AQUA);
            List<FormattedCharSequence> lines = font.split(label, Math.max(50, tocWidth - 14 - indent));
            int height = Math.max(18, lines.size() * LINE_HEIGHT + 5);
            entries.add(new RenderEntry(
                    Kind.TOC_ROW,
                    x,
                    y,
                    tocWidth,
                    height,
                    List.of(new Cell(7 + indent, 2, tocWidth - 14 - indent, lines)),
                    0,
                    item
            ));
            y += height;
        }

        return y + 10;
    }

    protected int layoutInfobox(int x, int y, int width) {
        String title = page.infobox().title().isBlank() ? page.title() : page.infobox().title();
        List<FormattedCharSequence> titleLines = font.split(
                Component.literal(title).withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD),
                width - 16
        );
        int titleHeight = Math.max(25, titleLines.size() * LINE_HEIGHT + 12);
        entries.add(new RenderEntry(Kind.INFOBOX_TITLE, x, y, width, titleHeight,
                List.of(new Cell(8, 6, width - 16, titleLines)), rarityColor(page.infobox().findTextValue("Rarity")), null));
        findTargets.add(new FindTarget(title, x, y, width, titleHeight));
        y += titleHeight;

        for (WikiInfobox.Entry entry : page.infobox().entries()) {
            if (entry instanceof WikiInfobox.Image image) {
                int captionHeight = image.caption().isBlank() ? 0 : LINE_HEIGHT + 7;
                int h = imageBoxHeight(image.image(), width - 16, 48, 118) + captionHeight + 12;
                entries.add(new RenderEntry(Kind.INFOBOX_IMAGE, x, y, width, h, List.of(), captionHeight, image));
                y += h;
            } else if (entry instanceof WikiInfobox.SlotStrip strip) {
                int rows = Math.max(1, (strip.slots().size() + 5) / 6);
                int h = rows * 23 + 8;
                entries.add(new RenderEntry(Kind.INFOBOX_SLOTS, x, y, width, h, List.of(), 0, strip));
                y += h;
            } else if (entry instanceof WikiInfobox.PanelTabs tabs) {
                int h = 20;
                entries.add(new RenderEntry(Kind.INFOBOX_TABS, x, y, width, h, List.of(), tabs.activeIndex(), tabs));
                y += h;
            } else if (entry instanceof WikiInfobox.Header header) {
                List<FormattedCharSequence> lines = font.split(toComponent(header.text()), width - 12);
                int h = Math.max(18, lines.size() * LINE_HEIGHT + 6);
                entries.add(new RenderEntry(Kind.INFOBOX_HEADER, x, y, width, h,
                        List.of(new Cell(6, 3, width - 12, lines)), 0, null));
                findTargets.add(new FindTarget(header.text().plainText(), x, y, width, h));
                y += h;
            } else if (entry instanceof WikiInfobox.Row row) {
                int labelWidth = Math.min(90, Math.max(68, width / 3));
                List<FormattedCharSequence> label = font.split(toComponent(row.label()), labelWidth - 8);
                List<FormattedCharSequence> value = font.split(toComponent(row.value().text()), width - labelWidth - 10);
                int lines = Math.max(label.size(), value.size());
                int h = Math.max(17, lines * LINE_HEIGHT + 6);
                entries.add(new RenderEntry(Kind.INFOBOX_ROW, x, y, width, h, List.of(
                        new Cell(4, 3, labelWidth - 8, label),
                        new Cell(labelWidth + 4, 3, width - labelWidth - 8, value)
                ), labelWidth, row));
                String rowSearchText = row.label().plainText() + " " + searchableContent(row.value());
                findTargets.add(new FindTarget(rowSearchText.trim(), x, y, width, h));
                y += h;
                if (!row.value().itemSlots().isEmpty()) {
                    WikiInfobox.SlotStrip strip = new WikiInfobox.SlotStrip(row.value().itemSlots());
                    entries.add(new RenderEntry(Kind.INFOBOX_SLOTS, x, y, width, 31, List.of(), 0, strip));
                    y += 31;
                }
            }
        }
        return y;
    }

    protected int layoutBlock(WikiBlock block, int x, int y, int width) {
        int startY = y;
        int endY = layoutBlockInternal(block, x, y, width);
        String searchable = searchableText(block);
        if (!searchable.isBlank() && endY > startY) {
            findTargets.add(new FindTarget(searchable, x, startY, width, Math.max(12, endY - startY)));
        }
        return endY;
    }

    protected int layoutBlockInternal(WikiBlock block, int x, int y, int width) {
        if (block instanceof WikiBlock.Heading heading) {
            MutableComponent component = toComponent(heading.text()).withStyle(ChatFormatting.BOLD);
            List<FormattedCharSequence> lines = font.split(component, width - (heading.level() > 2 ? 6 : 0));
            int h = Math.max(18, lines.size() * LINE_HEIGHT + 7);
            entries.add(new RenderEntry(heading.level() == 2 ? Kind.H2 : Kind.H3, x, y + 4, width, h,
                    List.of(new Cell(heading.level() > 2 ? 6 : 0, 0, width - 6, lines)), 0, null));
            return y + h + 10;
        }
        if (block instanceof WikiBlock.Paragraph paragraph) {
            return layoutContent(paragraph.content(), x, y, width, false);
        }
        if (block instanceof WikiBlock.ListItem item) {
            int indent = 12 + Math.min(item.depth(), 5) * 12;
            MutableComponent prefix = Component.literal(item.ordered() ? "1. " : "\u2022 ").withStyle(ChatFormatting.GRAY);
            MutableComponent text = prefix.append(toComponent(item.content().text()));
            List<FormattedCharSequence> lines = font.split(text, Math.max(50, width - indent));
            int h = Math.max(LINE_HEIGHT, lines.size() * LINE_HEIGHT);
            entries.add(new RenderEntry(Kind.TEXT, x + indent, y, width - indent, h,
                    List.of(new Cell(0, 0, width - indent, lines)), 0, null));
            return y + h + 4;
        }
        if (block instanceof WikiBlock.Table table) {
            return layoutTable(table, x, y, width);
        }
        if (block instanceof WikiBlock.TabGroup tabs) {
            return layoutTabs(tabs, x, y, width);
        }
        if (block instanceof WikiBlock.Crafting crafting) {
            return layoutCrafting(crafting.grid(), x, y, width);
        }
        if (block instanceof WikiBlock.Image image) {
            int imageWidth = preferredImageWidth(image.image(), width, 300);
            int captionHeight = image.caption().isBlank() ? 0 : LINE_HEIGHT + 6;
            int h = imageBoxHeight(image.image(), imageWidth - 8, 36, 160) + captionHeight + 8;
            entries.add(new RenderEntry(Kind.IMAGE, x, y, imageWidth, h, List.of(), captionHeight, image));
            return y + h + 8;
        }
        if (block instanceof WikiBlock.HorizontalRule) {
            entries.add(new RenderEntry(Kind.HR, x, y + 4, width, 1, List.of(), 0, null));
            return y + 10;
        }
        return y;
    }

    protected int layoutContent(WikiContent content, int x, int y, int width, boolean compact) {
        if (!content.text().isBlank()) {
            List<FormattedCharSequence> lines = font.split(toComponent(content.text()), width);
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
                List<FormattedCharSequence> lines = font.split(toComponent(source.content().text()), innerWidth);
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
                    cell.content()
            ));
        }

        int totalHeight = rowTops[rowCount];
        entries.add(new RenderEntry(
                Kind.TABLE,
                x,
                y,
                width,
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
            int step = compactSlotStep(width);
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
            height += (hasPrevious ? 3 : 0) + imageBoxHeight(image, width, 18, 46);
            hasPrevious = true;
        }

        return Math.max(LINE_HEIGHT, height);
    }

    protected static String searchableText(WikiBlock block) {
        if (block instanceof WikiBlock.Heading heading) {
            return heading.text().plainText();
        }
        if (block instanceof WikiBlock.Paragraph paragraph) {
            return searchableContent(paragraph.content());
        }
        if (block instanceof WikiBlock.ListItem item) {
            return searchableContent(item.content());
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

    protected int layoutTabs(WikiBlock.TabGroup group, int x, int y, int width) {
        if (group.tabs().isEmpty()) {
            return y;
        }
        int groupId = nextTabGroup++;
        int selected = selectedTabs.getOrDefault(groupId, group.initiallySelectedIndex());
        selected = Math.max(0, Math.min(selected, group.tabs().size() - 1));
        selectedTabs.put(groupId, selected);

        List<TabButton> buttons = new ArrayList<>();
        int bx = x;
        int by = y;
        for (int index = 0; index < group.tabs().size(); index++) {
            String title = group.tabs().get(index).title();
            int buttonWidth = Math.max(48, Math.min(145, font.width(title) + 16));
            if (bx + buttonWidth > x + width && bx > x) {
                bx = x;
                by += 20;
            }
            buttons.add(new TabButton(bx, by, buttonWidth, 19, index, title));
            bx += buttonWidth + 2;
        }
        int barHeight = by - y + 19;
        entries.add(new RenderEntry(Kind.TABS, x, y, width, barHeight, List.of(), groupId,
                new TabPayload(buttons, selected)));
        int contentY = y + barHeight + 5;
        int start = contentY + 7;
        for (WikiBlock block : group.tabs().get(selected).blocks()) {
            start = layoutBlock(block, x + 7, start, width - 14);
        }
        int contentHeight = Math.max(20, start - contentY + 3);
        entries.add(new RenderEntry(Kind.TAB_BORDER, x, contentY, width, contentHeight, List.of(), 0, null));
        return contentY + contentHeight + 9;
    }

    protected int layoutCrafting(WikiCraftingGrid grid, int x, int y, int width) {
        int cardWidth = Math.min(width, 284);
        int h = 111;
        entries.add(new RenderEntry(Kind.CRAFTING, x, y, cardWidth, h, List.of(), 0, grid));
        return y + h + 9;
    }

    protected MutableComponent toComponent(WikiText text) {
        MutableComponent result = Component.empty();
        for (WikiText.Span span : text.spans()) {
            MutableComponent part = Component.literal(span.text());
            part.withStyle(span.isLink() ? ChatFormatting.AQUA : ChatFormatting.WHITE);
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
                            .withClickEvent(new ClickEvent.OpenUrl(uri))
                            .withUnderlined(true));
                }
            }
            result.append(part);
        }
        return result;
    }


    protected void updateMaxScroll() {
        int visible = Math.max(1, height - HEADER_HEIGHT - BROWSER_TAB_HEIGHT - TOOLBAR_HEIGHT - FOOTER_HEIGHT - 15);
        maxScrollPixels = Math.max(0, documentHeight - visible);
        scrollPixels = Math.max(0, Math.min(scrollPixels, maxScrollPixels));
    }

}
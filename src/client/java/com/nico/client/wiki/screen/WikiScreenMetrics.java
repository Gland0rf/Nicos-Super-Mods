package com.nico.client.wiki.screen;

import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiContent;
import com.nico.client.wiki.WikiImage;
import com.nico.client.wiki.service.WikiStatRegistry;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Shared layout calculations used by both layout and rendering layers. */
final class WikiScreenMetrics {
    private WikiScreenMetrics() { }

    static boolean contains(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    static int rarityColor(String rarity) {
        String value = rarity == null ? "" : rarity.toUpperCase(Locale.ROOT);
        if (value.contains("MYTHIC")) return 0xFFFF55FF;
        if (value.contains("LEGENDARY")) return 0xFFFFAA00;
        if (value.contains("EPIC")) return 0xFFAA00AA;
        if (value.contains("RARE")) return 0xFF5555FF;
        if (value.contains("UNCOMMON")) return 0xFF55FF55;
        if (value.contains("DIVINE")) return 0xFF55FFFF;
        if (value.contains("SPECIAL")) return 0xFFFF5555;
        return WikiScreenBase.LINK;
    }

    static int compactSlotStep(int width) {
        if (width >= 90) return 30;
        if (width >= 55) return 24;
        return 18;
    }

    static int compactSlotStep(int width, int slotCount) {
        int base = compactSlotStep(width);

        // Armor set / equipment tables often contain a small, fixed number of
        // item slots that are much easier to scan when they stay on one row.
        // If we can fit them by shrinking the slots slightly, prefer that over
        // wrapping onto a second line.
        if (slotCount >= 2 && slotCount <= 4) {
            int fitted = Math.max(18, width / 4);
            return Math.min(base, fitted);
        }

        return base;
    }

    static int compactCraftingSlotSize(int width) {
        int preferred;
        if (width >= 220) preferred = 28;
        else if (width >= 170) preferred = 22;
        else if (width >= 125) preferred = 18;
        else preferred = 14;

        // Renderer footprint is roughly 4 * slotSize + 36px. Shrink further
        // for narrow table cells instead of letting the output slot escape the
        // cell border.
        int fitting = Math.max(8, (width - 36) / 4);
        return Math.max(8, Math.min(preferred, fitting));
    }

    static int compactCraftingHeight(int width) {
        return (compactCraftingSlotSize(width) + 2) * 3 + 3;
    }

    static boolean isImageOnlyTableContent(WikiContent content) {
        return content != null
                && content.text().isBlank()
                && content.itemSlots().isEmpty()
                && content.craftingGrids().isEmpty()
                && content.images().size() == 1;
    }

    static int tableImageHeight(WikiContent content, WikiImage image, int width) {
        return isImageOnlyTableContent(content)
                ? WikiScreenLayout.imageBoxHeight(image, width, 28, 84)
                : WikiScreenLayout.imageBoxHeight(image, width, 18, 46);
    }

    static int[] columnWidthsForTable(WikiBlock.Table table, int width, int count) {
        StringBuilder headerText = new StringBuilder();
        if (!table.rows().isEmpty()) {
            for (WikiBlock.Table.Cell cell : table.rows().get(0).cells()) {
                if (!headerText.isEmpty()) headerText.append(' ');
                headerText.append(cell.content().plainText().toLowerCase(Locale.ROOT));
            }
        }

        if (headerText.toString().contains("crafting recipe")) {
            if (count == 5) return proportionalWidths(width, 20, 7, 20, 23, 30);
            if (count == 4) return proportionalWidths(width, 20, 19, 25, 36);
        }

        return intrinsicColumnWidths(table, width, count);
    }

    static int[] intrinsicColumnWidths(WikiBlock.Table table, int width, int count) {
        int[] desired = new int[count];
        int[] minimum = new int[count];
        boolean[] flexible = new boolean[count];

        Arrays.fill(desired, 22);
        Arrays.fill(minimum, 22);

        boolean[][] occupied = new boolean[table.rows().size()][count];

        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            WikiBlock.Table.Row row = table.rows().get(rowIndex);
            int searchColumn = 0;

            for (WikiBlock.Table.Cell cell : row.cells()) {
                while (searchColumn < count && occupied[rowIndex][searchColumn]) {
                    searchColumn++;
                }

                if (searchColumn >= count) break;

                int columnSpan = Math.max(1, Math.min(cell.columnSpan(), count - searchColumn));
                int rowSpan = Math.max(1, Math.min(cell.rowSpan(), table.rows().size() - rowIndex));

                for (int coveredRow = rowIndex; coveredRow < rowIndex + rowSpan; coveredRow++) {
                    for (int coveredColumn = searchColumn; coveredColumn < searchColumn + columnSpan; coveredColumn++) {
                        occupied[coveredRow][coveredColumn] = true;
                    }
                }

                // Only use normal single-column cells to estimate a column's width.
                if (columnSpan == 1) {
                    WikiContent content = cell.content();
                    String plain = content.plainText().trim();

                    boolean compactVisual = isCompactVisualContent(content);
                    int contentWidth = estimatedTableContentWidth(content, cell.header());
                    desired[searchColumn] = Math.max(desired[searchColumn], contentWidth);

                    if (compactVisual) {
                        minimum[searchColumn] = Math.max(minimum[searchColumn], 20);
                    } else if (!content.itemSlots().isEmpty()) {
                        int slots = content.itemSlots().size();

                        minimum[searchColumn] = Math.max(
                                minimum[searchColumn],
                                Math.min(110, slots * 18 + 10)
                        );
                    } else if (!plain.isBlank()) {
                        minimum[searchColumn] = Math.max(
                                minimum[searchColumn],
                                Math.min(54, estimatedTextWidth(plain) + 12)
                        );
                    }

                    // Long text columns get the spare room.
                    if (!compactVisual && (plain.length() >= 18 || plain.indexOf(' ') >= 0)) {
                        flexible[searchColumn] = true;
                    }
                }

                searchColumn += columnSpan;
            }
        }

        return fitColumnWidth(width, desired, minimum, flexible);
    }

    static boolean isCompactVisualContent(WikiContent content) {
        if (content == null || !content.itemSlots().isEmpty() || !content.craftingGrids().isEmpty()) {
            return false;
        }

        String text = content.plainText().trim();
        return content.images().size() <= 1 && (text.isBlank() || text.length() <= 2);
    }

    static int estimatedTableContentWidth(WikiContent content, boolean header) {
        if (content == null) return 22;

        String plain = content.plainText().trim();
        int desired = plain.isBlank() ? 0 : estimatedTextWidth(plain) + (header ? 18 : 14);

        // Item strips, such as armor pieces, etc.
        if (!content.itemSlots().isEmpty()) {
            int slotCount = content.itemSlots().size();
            desired = Math.max(desired, Math.min(150, slotCount * 24 + 10));
        }

        // Images / icons.
        if (!content.images().isEmpty()) {
            for (WikiImage image : content.images()) {
                int imageWidth = image.declaredWidth() > 0 ? image.declaredWidth() : 28;
                desired = Math.max(desired, Math.min(96, Math.max(28, imageWidth) + 12));
            }
        }

        if (!content.craftingGrids().isEmpty()) {
            desired = Math.max(desired, 128);
        }

        if (isCompactVisualContent(content)) {
            desired = Math.min(Math.max(22, desired), 42);
        }

        return Math.max(22, Math.min(300, desired));
    }

    static int estimatedTextWidth(String text) {
        if (text == null || text.isBlank()) return 0;

        int widest = 0;
        int current = 0;

        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);

            if (ch == '\n' || ch == '\r') {
                widest = Math.max(widest, current);
                current = 0;
                continue;
            }

            if (ch == ' '
                    || ch == '.'
                    || ch == ','
                    || ch == ':'
                    || ch == ';'
                    || ch == '!'
                    || ch == '|'
                    || ch == 'i'
                    || ch == 'l') {
                current += 3;
            } else if (ch == 'W'
                    || ch == 'M'
                    || ch == '@'
                    || ch == '#'
                    || ch == '%'
                    || ch == '&') {
                current += 7;
            } else {
                current += 6;
            }
        }

        return Math.max(widest, current);
    }

    static int[] fitColumnWidth(int width, int[] desired, int[] minimum, boolean[] flexible) {
        int count = desired.length;
        int[] result = new int[count];
        int desiredTotal = sum(desired, 0, count);

        // Everything fits naturally.
        if (desiredTotal <= width) {
            System.arraycopy(desired, 0 , result, 0, count);
            return result;
        }

        // Not enough room for desired sizes.
        int minimumTotal = sum(minimum, 0, count);
        if (minimumTotal >= width) {
            return proportionalWidths(width, desired);
        }

        System.arraycopy(minimum, 0, result, 0, count);

        int remaining = width - minimumTotal;
        int totalDemand = 0;

        for (int index = 0; index < count; index++) {
            totalDemand += Math.max(0, desired[index] - minimum[index]);
        }

        int used = 0;
        for (int index = 0; index < count; index++) {
            int demand = Math.max(0, desired[index] - minimum[index]);
            int add = index == count - 1
                    ? remaining - used
                    : (totalDemand <= 0
                        ? 0
                        : remaining * demand / totalDemand);

            result[index] += Math.max(0, add);
            used += Math.max(0, add);
        }

        return result;
    }

    static int[] proportionalWidths(int width, int... weights) {
        int totalWeight = 0;
        for (int weight : weights) totalWeight += Math.max(0, weight);
        if (totalWeight <= 0) return columnWidths(width, weights.length);

        int[] result = new int[weights.length];
        int used = 0;
        for (int index = 0; index < weights.length; index++) {
            result[index] = index == weights.length - 1
                    ? width - used
                    : Math.max(1, width * weights[index] / totalWeight);
            used += result[index];
        }
        return result;
    }

    static int[] columnWidths(int width, int count) {
        int safeCount = Math.max(1, count);
        int[] result = new int[safeCount];
        int base = width / safeCount;
        int used = 0;
        for (int index = 0; index < safeCount; index++) {
            result[index] = index == safeCount - 1 ? width - used : base;
            used += result[index];
        }
        return result;
    }

    static int sum(int[] values, int from, int toExclusive) {
        int result = 0;
        for (int index = Math.max(0, from); index < toExclusive && index < values.length; index++) {
            result += values[index];
        }
        return result;
    }
}

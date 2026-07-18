package com.nico.client.wiki.screen;

import com.nico.client.wiki.WikiBlock;

import java.util.Locale;

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

    static int compactCraftingSlotSize(int width) {
        if (width >= 220) return 28;
        if (width >= 170) return 22;
        if (width >= 125) return 18;
        return 14;
    }

    static int compactCraftingHeight(int width) {
        return (compactCraftingSlotSize(width) + 2) * 3 + 3;
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
        return columnWidths(width, count);
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

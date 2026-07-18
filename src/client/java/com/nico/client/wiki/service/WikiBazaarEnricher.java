package com.nico.client.wiki.service;

import com.nico.client.utils.BazaarService;
import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiContent;
import com.nico.client.wiki.WikiInfobox;
import com.nico.client.wiki.WikiPage;
import com.nico.client.wiki.WikiText;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WikiBazaarEnricher {
    private static final Pattern TRAILING_TIER_ID = Pattern.compile("^(.*)_([0-9]+)$");
    private static final Pattern ROMAN_TIER = Pattern.compile("(?:^|\\s)([IVXLCDM]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_TIER = Pattern.compile("(?:^|\\s)([0-9]+)$");

    private static final ThreadLocal<DecimalFormat> COIN_FORMAT = ThreadLocal.withInitial(() -> {
        DecimalFormat format = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        format.setGroupingUsed(true);
        return format;
    });

    private WikiBazaarEnricher() { }

    public static CompletableFuture<WikiPage> enrich(
            WikiPage page,
            String requestedInternalId,
            BazaarService bazaarService
    ) {
        if (page == null || bazaarService == null) {
            return CompletableFuture.completedFuture(page);
        }

        String productId = firstNonBlank(page.infobox().findTextValue("Item ID"), requestedInternalId);
        if (productId.isBlank()) {
            return CompletableFuture.completedFuture(page);
        }

        return CompletableFuture.supplyAsync(() -> enrichBlocking(page, productId, bazaarService));
    }

    private static WikiPage enrichBlocking(
            WikiPage page,
            String requestedProductId,
            BazaarService bazaarService
    ) {
        try {
            BazaarService.BazaarSnapshot snapshot = bazaarService.getSnapshot();
            Map<String, BazaarService.BazaarProduct> products = snapshot.getProducts();
            if (products == null || products.isEmpty()) {
                System.err.println("[Wiki Bazaar] Bazaar snapshot contained no products");
                return page;
            }

            String normalizedProductId = requestedProductId.trim().toUpperCase(Locale.ROOT);
            BazaarService.BazaarProduct resolvedProduct = findProduct(products, normalizedProductId);
            if (resolvedProduct == null) {
                System.err.println("[Wiki Bazaar] Product not found: " + normalizedProductId);
            } else {
                System.out.println("[Wiki Bazaar] Loaded " + normalizedProductId
                        + " buy=" + resolvedProduct.getInstantBuyPrice()
                        + " sell=" + resolvedProduct.getInstantSellPrice());
            }
            WikiInfobox infobox = replaceBazaarStats(page.infobox(), resolvedProduct);
            List<WikiBlock> blocks = enrichBlocks(page.blocks(), normalizedProductId, products);

            if (infobox.equals(page.infobox()) && blocks.equals(page.blocks())) {
                return page;
            }

            return new WikiPage(
                    page.title(),
                    page.sourceName(),
                    page.pageUri(),
                    page.revisionId(),
                    infobox,
                    blocks
            );
        } catch (IOException | RuntimeException exception) {
            // Bazaar data is optional. A Wiki page should remain usable when the API is unavailable.
            System.err.println("[Wiki Bazaar] Could not enrich prices: " + exception.getMessage());
            exception.printStackTrace(System.err);
            return page;
        }
    }

    private static List<WikiBlock> enrichBlocks(
            List<WikiBlock> source,
            String requestedProductId,
            Map<String, BazaarService.BazaarProduct> products
    ) {
        List<WikiBlock> result = new ArrayList<>(source.size());

        for (WikiBlock block : source) {
            if (block instanceof WikiBlock.Table table) {
                result.add(enrichBazaarTable(table, requestedProductId, products));
                continue;
            }

            if (block instanceof WikiBlock.TabGroup group) {
                List<WikiBlock.TabGroup.Tab> tabs = new ArrayList<>(group.tabs().size());
                for (WikiBlock.TabGroup.Tab tab : group.tabs()) {
                    tabs.add(new WikiBlock.TabGroup.Tab(
                            tab.title(),
                            tab.panelId(),
                            enrichBlocks(tab.blocks(), requestedProductId, products)
                    ));
                }
                result.add(new WikiBlock.TabGroup(tabs, group.initiallySelectedIndex()));
                continue;
            }

            result.add(block);
        }

        return List.copyOf(result);
    }

    private static WikiBlock.Table enrichBazaarTable(
            WikiBlock.Table table,
            String requestedProductId,
            Map<String, BazaarService.BazaarProduct> products
    ) {
        List<RowLayout> layouts = buildRowLayouts(table);
        BazaarColumns columns = findBazaarColumns(table, layouts);
        if (columns == null) {
            return table;
        }

        String baseProductId = stripNumericTier(requestedProductId);
        List<WikiBlock.Table.Row> rows = new ArrayList<>(table.rows().size());
        boolean changed = false;

        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            WikiBlock.Table.Row row = table.rows().get(rowIndex);
            RowLayout layout = layouts.get(rowIndex);
            if (isHeaderRow(row)) {
                rows.add(row);
                continue;
            }

            int tier = extractTier(row);
            BazaarService.BazaarProduct product = resolveRowProduct(
                    products,
                    requestedProductId,
                    baseProductId,
                    tier
            );

            if (product == null) {
                rows.add(row);
                continue;
            }

            List<WikiBlock.Table.Cell> cells = new ArrayList<>(row.cells());
            boolean rowChanged = false;
            rowChanged |= replacePriceCell(cells, layout, columns.buyIndex(), product.getInstantBuyPrice());
            rowChanged |= replacePriceCell(cells, layout, columns.sellIndex(), product.getInstantSellPrice());
            rowChanged |= replacePriceCell(
                    cells,
                    layout,
                    columns.spreadIndex(),
                    product.getInstantBuyPrice() - product.getInstantSellPrice()
            );
            for (int offset = 0; offset < columns.changeSpan(); offset++) {
                rowChanged |= replaceUnavailableCell(
                        cells,
                        layout,
                        columns.changeStartIndex() + offset
                );
            }

            if (rowChanged) {
                rows.add(new WikiBlock.Table.Row(cells));
                changed = true;
            } else {
                rows.add(row);
            }
        }

        return changed ? new WikiBlock.Table(rows, table.sortable(), table.pixelated()) : table;
    }

    private static List<RowLayout> buildRowLayouts(WikiBlock.Table table) {
        int columnCount = Math.max(1, table.columnCount());
        int[] remainingRowSpans = new int[columnCount];
        List<RowLayout> result = new ArrayList<>(table.rows().size());

        for (WikiBlock.Table.Row row : table.rows()) {
            boolean[] occupied = new boolean[columnCount];
            int[] nextRowSpans = new int[columnCount];
            for (int column = 0; column < columnCount; column++) {
                occupied[column] = remainingRowSpans[column] > 0;
                nextRowSpans[column] = Math.max(0, remainingRowSpans[column] - 1);
            }

            int[] logicalToCell = new int[columnCount];
            java.util.Arrays.fill(logicalToCell, -1);
            int searchColumn = 0;

            for (int cellIndex = 0; cellIndex < row.cells().size(); cellIndex++) {
                WikiBlock.Table.Cell cell = row.cells().get(cellIndex);
                while (searchColumn < columnCount && occupied[searchColumn]) {
                    searchColumn++;
                }
                if (searchColumn >= columnCount) {
                    break;
                }

                int span = Math.min(cell.columnSpan(), columnCount - searchColumn);
                for (int offset = 0; offset < span; offset++) {
                    int logical = searchColumn + offset;
                    logicalToCell[logical] = cellIndex;
                    occupied[logical] = true;
                    if (cell.rowSpan() > 1) {
                        nextRowSpans[logical] = Math.max(nextRowSpans[logical], cell.rowSpan() - 1);
                    }
                }
                searchColumn += span;
            }

            result.add(new RowLayout(logicalToCell));
            remainingRowSpans = nextRowSpans;
        }

        return List.copyOf(result);
    }

    private static BazaarColumns findBazaarColumns(
            WikiBlock.Table table,
            List<RowLayout> layouts
    ) {
        for (int rowIndex = 0; rowIndex < table.rows().size(); rowIndex++) {
            WikiBlock.Table.Row row = table.rows().get(rowIndex);
            RowLayout layout = layouts.get(rowIndex);
            int buy = -1;
            int sell = -1;
            int spread = -1;
            int changeStart = -1;
            int changeSpan = 0;

            for (int logicalColumn = 0; logicalColumn < layout.logicalToCell().length; logicalColumn++) {
                int cellIndex = layout.cellIndex(logicalColumn);
                if (cellIndex < 0 || cellIndex >= row.cells().size()) {
                    continue;
                }

                String label = normalizeLabel(row.cells().get(cellIndex).content().plainText());
                if (label.equals("buy")) {
                    buy = logicalColumn;
                } else if (label.equals("sell")) {
                    sell = logicalColumn;
                } else if (label.equals("price spread")) {
                    spread = logicalColumn;
                } else if (label.equals("price change") && changeStart < 0) {
                    changeStart = logicalColumn;
                    changeSpan = Math.max(1, row.cells().get(cellIndex).columnSpan());
                }
            }

            if (buy >= 0 && sell >= 0 && spread >= 0 && changeStart >= 0) {
                return new BazaarColumns(buy, sell, spread, changeStart, changeSpan);
            }
        }
        return null;
    }

    private static boolean isHeaderRow(WikiBlock.Table.Row row) {
        for (WikiBlock.Table.Cell cell : row.cells()) {
            if (!cell.header()) {
                return false;
            }
        }
        return !row.cells().isEmpty();
    }

    private static int extractTier(WikiBlock.Table.Row row) {
        int inspected = 0;
        for (WikiBlock.Table.Cell cell : row.cells()) {
            if (inspected++ >= 2) {
                break;
            }

            String text = cell.content().plainText().trim();
            Matcher number = NUMBER_TIER.matcher(text);
            if (number.find()) {
                try {
                    return Integer.parseInt(number.group(1));
                } catch (NumberFormatException ignored) {
                    // Fall through to Roman numerals.
                }
            }

            Matcher roman = ROMAN_TIER.matcher(text);
            if (roman.find()) {
                int value = romanToInt(roman.group(1));
                if (value > 0) {
                    return value;
                }
            }
        }
        return 0;
    }

    private static BazaarService.BazaarProduct resolveRowProduct(
            Map<String, BazaarService.BazaarProduct> products,
            String requestedProductId,
            String baseProductId,
            int tier
    ) {
        if (tier > 0) {
            BazaarService.BazaarProduct tierProduct = findProduct(products, baseProductId + "_" + tier);
            if (tierProduct != null) {
                return tierProduct;
            }
        }

        BazaarService.BazaarProduct exact = findProduct(products, requestedProductId);
        if (exact != null) {
            return exact;
        }

        return findProduct(products, baseProductId);
    }

    private static boolean replacePriceCell(
            List<WikiBlock.Table.Cell> cells,
            RowLayout layout,
            int logicalIndex,
            double price
    ) {
        if (!Double.isFinite(price) || price <= 0.0D || logicalIndex < 0) {
            return false;
        }

        int cellIndex = layout.cellIndex(logicalIndex);
        if (cellIndex < 0 || cellIndex >= cells.size()) {
            return false;
        }

        WikiBlock.Table.Cell cell = cells.get(cellIndex);
        if (!cell.content().plainText().contains("???")) {
            return false;
        }

        WikiContent old = cell.content();
        WikiContent replacement = new WikiContent(
                WikiText.plain(formatCoins(price)),
                old.images(),
                old.itemSlots(),
                old.craftingGrids()
        );
        cells.set(cellIndex, new WikiBlock.Table.Cell(
                replacement,
                cell.header(),
                cell.rowSpan(),
                cell.columnSpan()
        ));
        return true;
    }

    private static boolean replaceUnavailableCell(
            List<WikiBlock.Table.Cell> cells,
            RowLayout layout,
            int logicalIndex
    ) {
        int cellIndex = layout.cellIndex(logicalIndex);
        if (cellIndex < 0 || cellIndex >= cells.size()) {
            return false;
        }

        WikiBlock.Table.Cell cell = cells.get(cellIndex);
        if (!cell.content().plainText().contains("???")) {
            return false;
        }

        WikiContent old = cell.content();
        WikiContent replacement = new WikiContent(
                WikiText.plain("N/A"),
                old.images(),
                old.itemSlots(),
                old.craftingGrids()
        );
        cells.set(cellIndex, new WikiBlock.Table.Cell(
                replacement,
                cell.header(),
                cell.rowSpan(),
                cell.columnSpan()
        ));
        return true;
    }

    private static WikiInfobox replaceBazaarStats(
            WikiInfobox infobox,
            BazaarService.BazaarProduct product
    ) {
        if (infobox == null || infobox.isEmpty() || product == null) {
            return infobox;
        }

        List<WikiInfobox.Entry> result = new ArrayList<>(infobox.entries().size());
        boolean inBazaarStats = false;

        for (WikiInfobox.Entry entry : infobox.entries()) {
            if (entry instanceof WikiInfobox.Header header) {
                inBazaarStats = normalizeLabel(header.text().plainText()).startsWith("bazaar stats");
                result.add(entry);
                continue;
            }

            if (!(entry instanceof WikiInfobox.Row row) || !inBazaarStats) {
                result.add(entry);
                continue;
            }

            Double replacement = switch (normalizeLabel(row.label().plainText())) {
                case "buy" -> product.getInstantBuyPrice();
                case "sell" -> product.getInstantSellPrice();
                case "price spread" -> product.getInstantBuyPrice() - product.getInstantSellPrice();
                default -> null;
            };

            if (replacement == null || !Double.isFinite(replacement) || replacement <= 0.0D) {
                result.add(entry);
                continue;
            }

            WikiContent oldContent = row.value();
            WikiContent newContent = new WikiContent(
                    WikiText.plain(formatCoins(replacement)),
                    oldContent.images(),
                    oldContent.itemSlots(),
                    oldContent.craftingGrids()
            );
            result.add(new WikiInfobox.Row(row.label(), newContent, row.groupColumns()));
        }

        return new WikiInfobox(infobox.title(), result);
    }

    private static BazaarService.BazaarProduct findProduct(
            Map<String, BazaarService.BazaarProduct> products,
            String productId
    ) {
        if (productId == null || productId.isBlank()) {
            return null;
        }
        BazaarService.BazaarProduct direct = products.get(productId);
        if (direct != null) {
            return direct;
        }
        return products.get(productId.toUpperCase(Locale.ROOT));
    }

    private static String stripNumericTier(String productId) {
        Matcher matcher = TRAILING_TIER_ID.matcher(productId == null ? "" : productId);
        return matcher.matches() ? matcher.group(1) : firstNonBlank(productId);
    }

    private static int romanToInt(String roman) {
        if (roman == null || roman.isBlank()) {
            return 0;
        }

        int total = 0;
        int previous = 0;
        String value = roman.toUpperCase(Locale.ROOT);
        for (int index = value.length() - 1; index >= 0; index--) {
            int current = switch (value.charAt(index)) {
                case 'I' -> 1;
                case 'V' -> 5;
                case 'X' -> 10;
                case 'L' -> 50;
                case 'C' -> 100;
                case 'D' -> 500;
                case 'M' -> 1000;
                default -> 0;
            };
            if (current < previous) {
                total -= current;
            } else {
                total += current;
                previous = current;
            }
        }
        return total;
    }

    private static String normalizeLabel(String value) {
        return value == null
                ? ""
                : value.replace('\u00A0', ' ')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String formatCoins(double value) {
        return COIN_FORMAT.get().format(value) + " Coins";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private record RowLayout(int[] logicalToCell) {
        private RowLayout {
            logicalToCell = logicalToCell == null ? new int[0] : logicalToCell.clone();
        }

        private int cellIndex(int logicalColumn) {
            return logicalColumn >= 0 && logicalColumn < logicalToCell.length
                    ? logicalToCell[logicalColumn]
                    : -1;
        }
    }

    private record BazaarColumns(
            int buyIndex,
            int sellIndex,
            int spreadIndex,
            int changeStartIndex,
            int changeSpan
    ) { }
}
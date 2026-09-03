package com.nico.client.wiki.service.bazaar;

import com.nico.client.utils.BazaarService;
import com.nico.client.wiki.WikiBlock;
import com.nico.client.wiki.WikiContent;
import com.nico.client.wiki.WikiInfobox;
import com.nico.client.wiki.WikiPage;
import com.nico.client.wiki.WikiText;
import com.nico.client.wiki.WikiTitleResolver;

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

/** Replaces the Wiki's JavaScript-only Bazaar placeholders with Hypixel API values. */
public final class WikiBazaarEnricher {
    private static final Pattern TRAILING_TIER_ID = Pattern.compile("^(.*)_([0-9]+)$");
    private static final Pattern ROMAN_TIER = Pattern.compile("(?:^|\\s)([IVXLCDM]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_TIER = Pattern.compile("(?:^|\\s)([0-9]+)$");
    private static final Pattern MATERIAL_QUANTITY = Pattern.compile(
            "([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*[x×]\\b",
            Pattern.CASE_INSENSITIVE
    );

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
        if (page == null) {
            return CompletableFuture.completedFuture(null);
        }

        String productId = firstNonBlank(page.infobox().findTextValue("Item ID"), requestedInternalId);
        return loadProducts(bazaarService)
                .thenApply(products -> enrichBlocking(page, productId, products))
                .exceptionally(throwable -> {
                    System.err.println("[NSM Wiki] Could not enrich Bazaar prices: "
                            + rootMessage(throwable));
                    return page;
                });
    }

    private static CompletableFuture<Map<String, PriceQuote>> loadProducts(BazaarService bazaarService) {
        if (bazaarService == null) {
            return WikiBazaarService.products().thenApply(WikiBazaarEnricher::fromFallbackProducts);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                BazaarService.BazaarSnapshot snapshot = bazaarService.getSnapshot();
                Map<String, BazaarService.BazaarProduct> source = snapshot == null
                        ? Map.of()
                        : snapshot.getProducts();
                return fromInjectedProducts(source);
            } catch (IOException | RuntimeException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).handle((products, throwable) -> {
            if (throwable == null && products != null && !products.isEmpty()) {
                return CompletableFuture.completedFuture(products);
            }
            /* The Wiki integration must work even if the rest of the mod never
             * called HypixelWikiService.setBazaarService(...). */
            return WikiBazaarService.products().thenApply(WikiBazaarEnricher::fromFallbackProducts);
        }).thenCompose(future -> future);
    }

    private static Map<String, PriceQuote> fromInjectedProducts(
            Map<String, BazaarService.BazaarProduct> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, PriceQuote> result = new java.util.HashMap<>();
        for (Map.Entry<String, BazaarService.BazaarProduct> entry : source.entrySet()) {
            BazaarService.BazaarProduct product = entry.getValue();
            if (product == null) {
                continue;
            }
            result.put(entry.getKey().toUpperCase(Locale.ROOT), new PriceQuote(
                    product.getInstantBuyPrice(),
                    product.getInstantSellPrice()
            ));
        }
        return Map.copyOf(result);
    }

    private static Map<String, PriceQuote> fromFallbackProducts(
            Map<String, WikiBazaarService.Product> source
    ) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, PriceQuote> result = new java.util.HashMap<>();
        for (Map.Entry<String, WikiBazaarService.Product> entry : source.entrySet()) {
            WikiBazaarService.Product product = entry.getValue();
            result.put(entry.getKey().toUpperCase(Locale.ROOT), new PriceQuote(
                    product.instantBuyPrice(),
                    product.instantSellPrice()
            ));
        }
        return Map.copyOf(result);
    }

    private static WikiPage enrichBlocking(
            WikiPage page,
            String requestedProductId,
            Map<String, PriceQuote> products
    ) {
        if (products == null || products.isEmpty()) {
            return page;
        }

        String normalizedProductId = requestedProductId == null
                ? ""
                : requestedProductId.trim().toUpperCase(Locale.ROOT);
        PriceQuote resolvedProduct = findProduct(products, normalizedProductId);
        WikiInfobox infobox = replaceBazaarStats(page.infobox(), resolvedProduct);
        infobox = replaceBazaarMaterialCosts(infobox, products);
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
    }

    private static List<WikiBlock> enrichBlocks(
            List<WikiBlock> source,
            String requestedProductId,
            Map<String, PriceQuote> products
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

    /**
     * Recognizes the Wiki's Bazaar table by its exact column labels. It does not
     * treat arbitrary tables containing question marks as Bazaar data.
     */
    private static WikiBlock.Table enrichBazaarTable(
            WikiBlock.Table table,
            String requestedProductId,
            Map<String, PriceQuote> products
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
            PriceQuote product = resolveRowProduct(
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
            rowChanged |= replacePriceCell(cells, layout, columns.buyIndex(), product.instantBuyPrice());
            rowChanged |= replacePriceCell(cells, layout, columns.sellIndex(), product.instantSellPrice());
            rowChanged |= replacePriceCell(
                    cells,
                    layout,
                    columns.spreadIndex(),
                    product.instantBuyPrice() - product.instantSellPrice()
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

    private static PriceQuote resolveRowProduct(
            Map<String, PriceQuote> products,
            String requestedProductId,
            String baseProductId,
            int tier
    ) {
        if (tier > 0) {
            PriceQuote tierProduct = findProduct(products, baseProductId + "_" + tier);
            if (tierProduct != null) {
                return tierProduct;
            }
        }

        PriceQuote exact = findProduct(products, requestedProductId);
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

    /**
     * Replaces the Wiki's LivePriceData "Bazaar Material cost" placeholders.
     * The raw-material rows contain human-readable item links rather than
     * product IDs, so resolve each linked name through Hypixel's item registry
     * and multiply its quantity by the mod's instant-buy Bazaar price.
     */
    private static WikiInfobox replaceBazaarMaterialCosts(
            WikiInfobox infobox,
            Map<String, PriceQuote> products
    ) {
        if (infobox == null || infobox.isEmpty() || products == null || products.isEmpty()) {
            return infobox;
        }

        List<WikiInfobox.Entry> result = new ArrayList<>(infobox.entries().size());
        List<MaterialRequirement> activeMaterials = List.of();
        boolean changed = false;

        for (WikiInfobox.Entry entry : infobox.entries()) {
            if (!(entry instanceof WikiInfobox.Row row)) {
                result.add(entry);
                continue;
            }

            String label = normalizeLabel(row.label().plainText());
            if (label.startsWith("raw materials")) {
                activeMaterials = parseMaterialRequirements(row.value().text());
                result.add(entry);
                continue;
            }

            if (!label.startsWith("bazaar material cost")
                    || !row.value().plainText().contains("???")
                    || activeMaterials.isEmpty()) {
                result.add(entry);
                continue;
            }

            Double total = calculateMaterialCost(activeMaterials, products);
            if (total == null || !Double.isFinite(total) || total <= 0.0D) {
                result.add(entry);
                continue;
            }

            WikiContent old = row.value();
            WikiContent replacement = new WikiContent(
                    WikiText.plain(formatCoins(total)),
                    old.images(),
                    old.itemSlots(),
                    old.craftingGrids()
            );
            result.add(new WikiInfobox.Row(row.label(), replacement, row.groupColumns()));
            changed = true;
        }

        return changed ? new WikiInfobox(infobox.title(), result) : infobox;
    }

    private static List<MaterialRequirement> parseMaterialRequirements(WikiText text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<MaterialRequirement> result = new ArrayList<>();
        StringBuilder pendingText = new StringBuilder();

        for (WikiText.Span span : text.spans()) {
            if (span.hasInlineImage()) {
                // The icon is part of the same material token; do not consume
                // the quantity that precedes the linked item name.
                continue;
            }
            if (!span.isLink()) {
                pendingText.append(span.text()).append(' ');
                continue;
            }

            double quantity = lastMaterialQuantity(pendingText.toString());
            String itemName = span.text().replaceAll("\\s+", " ").trim();
            if (quantity > 0.0D && !itemName.isBlank()) {
                result.add(new MaterialRequirement(itemName, quantity));
            }

            // Text after this link belongs to the next material.
            pendingText.setLength(0);
        }

        return List.copyOf(result);
    }

    private static double lastMaterialQuantity(String value) {
        Matcher matcher = MATERIAL_QUANTITY.matcher(value == null ? "" : value);
        double result = 0.0D;
        while (matcher.find()) {
            try {
                result = Double.parseDouble(matcher.group(1).replace(",", ""));
            } catch (NumberFormatException ignored) {
                // Continue in case a later quantity is valid.
            }
        }
        return result;
    }

    private static Double calculateMaterialCost(
            List<MaterialRequirement> requirements,
            Map<String, PriceQuote> products
    ) {
        double total = 0.0D;
        for (MaterialRequirement requirement : requirements) {
            PriceQuote product = findProduct(products, directProductId(requirement.itemName()));
            String resolvedId = "";
            if (product == null) {
                try {
                    resolvedId = WikiTitleResolver.resolveInternalItemId(requirement.itemName()).join();
                    product = findProduct(products, resolvedId);
                } catch (RuntimeException ignored) {
                    // The direct normalized ID still handles most Bazaar items.
                }
            }
            if (product == null) {
                System.err.println("[NSM Wiki] No Bazaar product for material "
                        + requirement.itemName() + (resolvedId.isBlank() ? "" : " (" + resolvedId + ")"));
                return null;
            }

            double price = product.instantBuyPrice();
            if (!Double.isFinite(price) || price <= 0.0D) {
                return null;
            }
            total += requirement.quantity() * price;
        }
        return total;
    }

    private static String directProductId(String itemName) {
        if (itemName == null || itemName.isBlank()) {
            return "";
        }
        return itemName
                .replace(' ', ' ')
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static WikiInfobox replaceBazaarStats(
            WikiInfobox infobox,
            PriceQuote product
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
                case "buy" -> product.instantBuyPrice();
                case "sell" -> product.instantSellPrice();
                case "price spread" -> product.instantBuyPrice() - product.instantSellPrice();
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

    private static PriceQuote findProduct(
            Map<String, PriceQuote> products,
            String productId
    ) {
        if (productId == null || productId.isBlank()) {
            return null;
        }
        PriceQuote direct = products.get(productId);
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

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null || current.getMessage() == null || current.getMessage().isBlank()
                ? "Bazaar data unavailable"
                : current.getMessage();
    }

    private record PriceQuote(double instantBuyPrice, double instantSellPrice) { }

    private record MaterialRequirement(String itemName, double quantity) { }

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
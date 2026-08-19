package com.nico.client.utils.tradeprot;

import com.nico.client.wiki.WikiContent;
import com.nico.client.wiki.WikiInfobox;
import com.nico.client.wiki.WikiPage;
import com.nico.client.wiki.WikiText;
import com.nico.client.wiki.WikiTitleResolver;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class WikiAuctionHouseEnricher {
    private static final ThreadLocal<DecimalFormat> COIN_FORMAT = ThreadLocal.withInitial(() -> {
        DecimalFormat format = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.US));
        format.setGroupingUsed(true);
        return format;
    });

    private WikiAuctionHouseEnricher() { }

    public static CompletableFuture<WikiPage> enrich(WikiPage page, String requestedInternalId) {
        if (page == null || page.infobox().isEmpty()) {
            return CompletableFuture.completedFuture(page);
        }

        WikiPage basePage = removeDailyAverageRow(page);
        if (!hasAuctionHouseRows(basePage.infobox())) {
            return CompletableFuture.completedFuture(basePage);
        }

        String itemId = firstNonBlank(basePage.infobox().findTextValue("Item ID"), requestedInternalId);
        CompletableFuture<String> itemIdFuture = itemId.isBlank()
                ? WikiTitleResolver.resolveInternalItemId(basePage.title())
                : CompletableFuture.completedFuture(itemId);

        return itemIdFuture
                .thenCompose(resolvedId -> WikiAuctionHouseService.lowestBin(resolvedId, basePage.title()))
                .thenApply(lowestBin -> replaceAuctionHouseRows(basePage, lowestBin))
                .exceptionally(throwable -> basePage);
    }

    private static WikiPage removeDailyAverageRow(WikiPage page) {
        List<WikiInfobox.Entry> result = new ArrayList<>(page.infobox().entries().size());
        boolean inAuctionHouse = false;
        boolean changed = false;

        for (WikiInfobox.Entry entry : page.infobox().entries()) {
            if (entry instanceof WikiInfobox.Header header) {
                inAuctionHouse = normalizeLabel(header.text().plainText()).startsWith("auction house");
                result.add(entry);
                continue;
            }
            if (inAuctionHouse && entry instanceof WikiInfobox.Row row) {
                String label = normalizeLabel(row.label().plainText());
                if (label.contains("lowest bin") && label.contains("daily average")) {
                    changed = true;
                    continue;
                }
            }
            result.add(entry);
        }

        if (!changed) {
            return page;
        }
        return withInfoboxEntries(page, result);
    }

    private static boolean hasAuctionHouseRows(WikiInfobox infobox) {
        boolean inAuctionHouse = false;
        for (WikiInfobox.Entry entry : infobox.entries()) {
            if (entry instanceof WikiInfobox.Header header) {
                inAuctionHouse = normalizeLabel(header.text().plainText()).startsWith("auction house");
                continue;
            }
            if (inAuctionHouse && entry instanceof WikiInfobox.Row row
                    && normalizeLabel(row.label().plainText()).contains("lowest bin")) {
                return true;
            }
        }
        return false;
    }

    private static WikiPage replaceAuctionHouseRows(
            WikiPage page,
            WikiAuctionHouseService.LowestBin lowestBin
    ) {
        List<WikiInfobox.Entry> result = new ArrayList<>(page.infobox().entries().size());
        boolean inAuctionHouse = false;
        boolean changed = false;

        for (WikiInfobox.Entry entry : page.infobox().entries()) {
            if (entry instanceof WikiInfobox.Header header) {
                inAuctionHouse = normalizeLabel(header.text().plainText()).startsWith("auction house");
                result.add(entry);
                continue;
            }

            if (!inAuctionHouse || !(entry instanceof WikiInfobox.Row row)) {
                result.add(entry);
                continue;
            }

            String label = normalizeLabel(row.label().plainText());
            if (!label.contains("lowest bin")) {
                result.add(entry);
                continue;
            }

            if (label.contains("daily average")) {
                // The public Hypixel API has no 24-hour LBIN history. Do not
                // show a misleading placeholder or use a third-party source.
                changed = true;
                continue;
            }

            String visible = lowestBin.available()
                    ? COIN_FORMAT.get().format(lowestBin.coins()) + " Coins"
                    : "No active BIN";
            String hoverText = lowestBin.available()
                    ? "Live value from Hypixel's active-auctions API. Cached for up to 15 minutes; press Reload to refresh immediately."
                    : firstNonBlank(lowestBin.error(), "No active BIN listing was found for this item.");

            result.add(new WikiInfobox.Row(
                    row.label(),
                    replaceText(row.value(), visible, "Lowest BIN", hoverText),
                    row.groupColumns()
            ));
            changed = true;
        }

        if (!changed) {
            return page;
        }

        return withInfoboxEntries(page, result);
    }

    private static WikiPage withInfoboxEntries(WikiPage page, List<WikiInfobox.Entry> entries) {
        WikiInfobox infobox = new WikiInfobox(page.infobox().title(), entries);
        return new WikiPage(
                page.title(),
                page.sourceName(),
                page.pageUri(),
                page.revisionId(),
                infobox,
                page.blocks()
        );
    }

    private static WikiContent replaceText(WikiContent old, String visible, String hoverTitle, String hoverText) {
        WikiText text = new WikiText(List.of(new WikiText.Span(
                visible,
                "",
                false,
                false,
                "",
                hoverTitle,
                hoverText
        )));
        return new WikiContent(text, old.images(), old.itemSlots(), old.craftingGrids());
    }

    private static String normalizeLabel(String value) {
        return value == null ? "" : value
                .replace('\u00A0', ' ')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
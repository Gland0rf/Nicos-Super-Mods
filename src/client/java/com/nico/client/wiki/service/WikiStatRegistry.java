package com.nico.client.wiki.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WikiStatRegistry extends WikiServiceSupport {
    private static final long REFRESH_INTERVAL =
            Duration.ofHours(12).toMillis();

    private static volatile Set<String> stats = Set.of();
    private static volatile long lastRefresh = 0L;

    private static final AtomicBoolean refreshing =
            new AtomicBoolean(false);

    private WikiStatRegistry() {
    }

    public static boolean isStat(String text) {
        ensureFresh();
        return stats.contains(normalize(text));
    }

    public static Set<String> currentStats() {
        ensureFresh();
        return stats;
    }

    public static void ensureFresh() {
        long now = System.currentTimeMillis();

        if (!stats.isEmpty()
                && now - lastRefresh < REFRESH_INTERVAL) {
            return;
        }

        refreshAsync();
    }

    public static void refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }

        URI uri = buildApiUri(Map.of(
                "action", "parse",
                "format", "json",
                "formatversion", "2",
                "page", "Stats",
                "prop", "text|revid",
                "disableeditsection", "1",
                "redirects", "1"
        ));

        HttpRequest request = requestBuilder(uri)
                .header("Accept", "application/json")
                .GET()
                .build();

        HTTP_CLIENT.sendAsync(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                )
                .thenApply(WikiStatRegistry::validateResponse)
                .thenApply(WikiStatRegistry::parseStats)
                .whenComplete((result, throwable) -> {
                    try {
                        if (throwable == null && !result.isEmpty()) {
                            stats = Set.copyOf(result);
                            lastRefresh = System.currentTimeMillis();

                            if (DEBUG) {
                                System.out.println(
                                        "[NSM Wiki] Loaded "
                                                + stats.size()
                                                + " SkyBlock stats"
                                );
                            }
                        } else if (throwable != null && DEBUG) {
                            System.err.println(
                                    "[NSM Wiki] Could not refresh stats: "
                                            + throwable.getMessage()
                            );
                        }
                    } finally {
                        refreshing.set(false);
                    }
                });
    }

    private static Set<String> parseStats(String body) {
        JsonObject root = JsonParser.parseString(body)
                .getAsJsonObject();

        if (!root.has("parse")
                || !root.get("parse").isJsonObject()) {
            return Set.of();
        }

        JsonObject parse = root.getAsJsonObject("parse");

        if (!parse.has("text")) {
            return Set.of();
        }

        String html = parse.get("text").getAsString();

        Document document = Jsoup.parse(
                html,
                WIKI_ARTICLE_BASE + "Stats"
        );

        Set<String> result = new LinkedHashSet<>();

        for (Element table : document.select("table")) {
            Element firstRow = table.selectFirst("tr");

            if (firstRow == null) {
                continue;
            }

            Elements headerCells =
                    firstRow.select(":scope > th, :scope > td");

            if (headerCells.isEmpty()) {
                continue;
            }

            // Only consume actual stat tables.
            if (!normalize(headerCells.first().text()).equals("stat")) {
                continue;
            }

            for (Element row : table.select("tr")) {
                Elements cells =
                        row.select(":scope > th, :scope > td");

                if (cells.isEmpty()) {
                    continue;
                }

                String stat = normalizeStatName(
                        cells.first().text()
                );

                if (!stat.isBlank() && !stat.equals("stat")) {
                    result.add(stat);
                }
            }
        }

        return result;
    }

    private static String normalizeStatName(String text) {
        if (text == null) {
            return "";
        }

        /*
         * Stat cells usually start with the wiki's custom stat icon.
         * Remove private-use glyphs before comparing the name.
         */
        return normalize(
                text.replaceAll("[\\uE000-\\uF8FF]", "")
        );
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }

        return text
                .replace('\u00A0', ' ')
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }
}
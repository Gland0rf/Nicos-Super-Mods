package com.nico.client.wiki.service;

import com.google.gson.JsonObject;
import com.nico.client.utils.BazaarService;
import com.google.gson.JsonParser;
import com.nico.client.wiki.*;
import net.minecraft.world.item.ItemStack;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class HypixelWikiService extends WikiArticleParser {
    private static final Map<String, CompletableFuture<WikiPage>> CACHE = new ConcurrentHashMap<>();
    private static volatile BazaarService bazaarService;

    private HypixelWikiService() { }

    public static void setBazaarService(BazaarService service) {
        bazaarService = service;
        CACHE.clear();
        System.out.println("[Wiki] Bazaar service configured: " + (service != null));
    }

    public static boolean isBazaarServiceConfigured() {
        return bazaarService != null;
    }

    public static CompletableFuture<WikiPage> findPage(ItemStack itemStack) {
        SkyblockItemResolver.ItemIdentity identity = SkyblockItemResolver.resolveIdentity(itemStack);
        String key = cacheKey(identity);
        return CACHE.computeIfAbsent(key, ignored -> resolveAndFetch(identity)
                .whenComplete((page, throwable) -> {
                    if (throwable != null) {
                        CACHE.remove(key);
                    }
                }));
    }

    public static CompletableFuture<WikiPage> reloadPage(ItemStack itemStack) {
        SkyblockItemResolver.ItemIdentity identity = SkyblockItemResolver.resolveIdentity(itemStack);
        CACHE.remove(cacheKey(identity));
        return findPage(itemStack);
    }

    public static CompletableFuture<WikiPage> findPage(String exactDisplayName) {
        SkyblockItemResolver.ItemIdentity identity = new SkyblockItemResolver.ItemIdentity("", exactDisplayName);
        String key = cacheKey(identity);
        return CACHE.computeIfAbsent(key, ignored -> resolveAndFetch(identity)
                .whenComplete((page, throwable) -> {
                    if (throwable != null) {
                        CACHE.remove(key);
                    }
                }));
    }

    public static CompletableFuture<WikiPage> reloadPage(String exactDisplayName) {
        SkyblockItemResolver.ItemIdentity identity = new SkyblockItemResolver.ItemIdentity("", exactDisplayName);
        CACHE.remove(cacheKey(identity));
        return findPage(exactDisplayName);
    }


    public static CompletableFuture<WikiPage> findPageQuery(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The Wiki search query cannot be blank"));
        }
        String key = "query:" + query.toLowerCase(Locale.ROOT);
        return CACHE.computeIfAbsent(key, ignored -> WikiTitleResolver.resolveQuery(query)
                .thenCompose(HypixelWikiService::fetchParsedPage)
                .thenCompose(page -> WikiBazaarEnricher.enrich(page,
                        query.matches("[A-Z0-9_]{3,}") ? query : "",
                        bazaarService))
                .whenComplete((page, throwable) -> {
                    if (throwable != null) {
                        CACHE.remove(key);
                    }
                }));
    }

    public static CompletableFuture<WikiPage> reloadPageQuery(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        CACHE.remove("query:" + query.toLowerCase(Locale.ROOT));
        return findPageQuery(query);
    }

    public static CompletableFuture<List<WikiTitleResolver.SearchResult>> search(String query, int limit) {
        return WikiTitleResolver.searchTitles(query, limit);
    }

    public static CompletableFuture<WikiPage> findPage(URI articleUri) {
        String title = articleTitleFromUri(articleUri);
        String key = "wiki:" + title.toLowerCase(Locale.ROOT);
        URI canonicalUri = articleUriForTitle(title);
        WikiTitleResolver.ResolvedWikiTitle resolved =
                new WikiTitleResolver.ResolvedWikiTitle(title, canonicalUri);

        return CACHE.computeIfAbsent(key, ignored -> fetchParsedPage(resolved)
                .thenCompose(page -> WikiBazaarEnricher.enrich(page, "", bazaarService))
                .whenComplete((page, throwable) -> {
                    if (throwable != null) {
                        CACHE.remove(key);
                    }
                }));
    }

    public static CompletableFuture<WikiPage> reloadPage(URI articleUri) {
        String title = articleTitleFromUri(articleUri);
        CACHE.remove("wiki:" + title.toLowerCase(Locale.ROOT));
        return findPage(articleUri);
    }

    public static boolean isWikiArticleUri(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath();
        return (host.equals("hypixelskyblock.minecraft.wiki")
                || host.equals("www.hypixelskyblock.minecraft.wiki"))
                && (path.startsWith("/w/") || path.startsWith("/wiki/"));
    }

    private static String articleTitleFromUri(URI uri) {
        if (!isWikiArticleUri(uri)) {
            throw new IllegalArgumentException("Not a Hypixel SkyBlock Wiki article URL: " + uri);
        }

        String path = uri.getRawPath();
        String prefix = path.startsWith("/w/") ? "/w/" : "/wiki/";
        String encodedTitle = path.substring(prefix.length());
        if (encodedTitle.isBlank()) {
            throw new IllegalArgumentException("Wiki article URL contained no title: " + uri);
        }

        return URLDecoder.decode(encodedTitle, StandardCharsets.UTF_8)
                .replace('_', ' ')
                .trim();
    }

    private static URI articleUriForTitle(String title) {
        String encoded = URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
        return URI.create(WIKI_ARTICLE_BASE + encoded);
    }

    private static String cacheKey(SkyblockItemResolver.ItemIdentity identity) {
        if (identity.hasInternalId()) {
            return "id:" + identity.internalId().toUpperCase(Locale.ROOT);
        }
        return "name:" + identity.displayName().toLowerCase(Locale.ROOT);
    }

    private static CompletableFuture<WikiPage> resolveAndFetch(SkyblockItemResolver.ItemIdentity identity) {
        return WikiTitleResolver.resolve(identity)
                .thenCompose(HypixelWikiService::fetchParsedPage)
                .thenCompose(page -> WikiBazaarEnricher.enrich(page, identity.internalId(), bazaarService));
    }

    private static CompletableFuture<WikiPage> fetchParsedPage(WikiTitleResolver.ResolvedWikiTitle resolved) {
        URI uri = buildApiUri(Map.of(
                "action", "parse",
                "format", "json",
                "formatversion", "2",
                "page", resolved.title(),
                "prop", "text|displaytitle|revid",
                "disableeditsection", "1",
                "redirects", "1"
        ));
        HttpRequest request = requestBuilder(uri)
                .header("Accept", "application/json")
                .GET()
                .build();
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(HypixelWikiService::validateResponse)
                .thenApply(body -> parseApiResponse(resolved, body));
    }

    private static WikiPage parseApiResponse(WikiTitleResolver.ResolvedWikiTitle resolved, String body) {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new WikiRequestException("The Wiki API returned invalid JSON", exception);
        }
        if (root.has("error") && root.get("error").isJsonObject()) {
            JsonObject error = root.getAsJsonObject("error");
            throw new WikiRequestException("Wiki API error " + jsonString(error, "code") + ": " + jsonString(error, "info"));
        }
        JsonObject parse = root.has("parse") && root.get("parse").isJsonObject()
                ? root.getAsJsonObject("parse")
                : null;
        if (parse == null) {
            throw new WikiRequestException("The Wiki API response contained no parse object");
        }
        String html = jsonString(parse, "text");
        if (html.isBlank()) {
            throw new WikiRequestException("The Wiki API returned no rendered HTML");
        }
        String title = jsonString(parse, "displaytitle");
        if (!title.isBlank()) {
            title = Jsoup.parse(title).text().trim();
        }
        if (title.isBlank()) {
            title = jsonString(parse, "title");
        }
        if (title.isBlank()) {
            title = resolved.title();
        }
        String revisionId = parse.has("revid") ? parse.get("revid").getAsString() : "";
        return parseRenderedArticle(title, resolved.pageUri(), revisionId, html);
    }


    public static class WikiRequestException extends RuntimeException {
        public WikiRequestException(String message) { super(message); }
        public WikiRequestException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class WikiContractException extends WikiRequestException {
        public WikiContractException(String message) { super("Unsupported Wiki markup: " + message); }
    }
}
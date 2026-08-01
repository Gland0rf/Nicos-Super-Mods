package com.nico.client.wiki;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves an item ID through Hypixel's resource registry, then resolves an exact MediaWiki page. */
public final class WikiTitleResolver {
    private static final String HYPIXEL_ITEMS_ENDPOINT = "https://api.hypixel.net/resources/skyblock/items";
    private static final String WIKI_API_ENDPOINT = "https://hypixelskyblock.minecraft.wiki/api.php";
    private static final String WIKI_ARTICLE_BASE = "https://hypixelskyblock.minecraft.wiki/w/";
    private static final long REGISTRY_TTL_MILLIS = Duration.ofHours(6).toMillis();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Map<String, String> ITEM_NAMES = new ConcurrentHashMap<>();
    private static volatile long registryLoadedAt;
    private static volatile CompletableFuture<Void> registryLoad;

    private WikiTitleResolver() { }

    public static CompletableFuture<ResolvedWikiTitle> resolve(SkyblockItemResolver.ItemIdentity identity) {
        CompletableFuture<Void> registry = identity.hasInternalId()
                ? ensureRegistryLoaded().exceptionally(throwable -> null)
                : CompletableFuture.completedFuture(null);

        return registry.thenCompose(ignored -> {
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            if (identity.hasInternalId()) {
                String registryName = ITEM_NAMES.get(identity.internalId().toUpperCase(Locale.ROOT));
                if (registryName != null && !registryName.isBlank()) {
                    candidates.add(registryName);
                }
            }
            if (!identity.displayName().isBlank()) {
                candidates.add(identity.displayName());
            }
            if (candidates.isEmpty()) {
                return CompletableFuture.failedFuture(new WikiResolutionException("No item identity was available"));
            }
            return resolveCandidate(candidates.stream().toList(), 0);
        });
    }


    /** Resolves either a Wiki title/search phrase or a Hypixel internal item ID. */
    public static CompletableFuture<ResolvedWikiTitle> resolveQuery(String rawQuery) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isBlank()) {
            return CompletableFuture.failedFuture(new WikiResolutionException("The Wiki search query was blank"));
        }

        CompletableFuture<ResolvedWikiTitle> exact;
        String normalizedItemId = normalizeItemIdQuery(query);
        if (!normalizedItemId.isBlank()) {
            exact = resolve(new SkyblockItemResolver.ItemIdentity(normalizedItemId, query));
        } else {
            exact = resolveExact(query).thenCompose(result -> result != null
                    ? CompletableFuture.completedFuture(result)
                    : CompletableFuture.failedFuture(new WikiResolutionException("No exact Wiki page matched " + query)));
        }

        return exact.exceptionallyCompose(ignored -> searchTitles(query, 1).thenCompose(results -> {
            if (results.isEmpty()) {
                return CompletableFuture.failedFuture(new WikiResolutionException("No Wiki page matched " + query));
            }
            SearchResult first = results.get(0);
            return CompletableFuture.completedFuture(new ResolvedWikiTitle(first.title(), first.pageUri()));
        }));
    }

    /** MediaWiki exact/prefix/full-text search used by the browser address bar. */
    public static CompletableFuture<java.util.List<SearchResult>> searchTitles(String rawQuery, int requestedLimit) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isBlank()) {
            return CompletableFuture.completedFuture(java.util.List.of());
        }
        int limit = Math.max(1, Math.min(12, requestedLimit));

        String normalizedItemId = normalizeItemIdQuery(query);
        CompletableFuture<java.util.List<SearchResult>> itemIdCandidate;
        if (!normalizedItemId.isBlank()) {
            itemIdCandidate = resolve(new SkyblockItemResolver.ItemIdentity(normalizedItemId, query))
                    .thenApply(result -> java.util.List.of(new SearchResult(result.title(), result.pageUri())))
                    .exceptionally(ignored -> java.util.List.<SearchResult>of());
        } else {
            itemIdCandidate = CompletableFuture.completedFuture(java.util.List.of());
        }

        CompletableFuture<java.util.List<SearchResult>> exactTitle = resolveExact(query)
                .thenApply(result -> result == null
                        ? java.util.List.<SearchResult>of()
                        : java.util.List.of(new SearchResult(result.title(), result.pageUri())))
                .exceptionally(ignored -> java.util.List.<SearchResult>of());

        URI prefixUri = buildApiUri(Map.of(
                "action", "query",
                "format", "json",
                "formatversion", "2",
                "list", "prefixsearch",
                "psnamespace", "0",
                "pslimit", Integer.toString(limit),
                "pssearch", query
        ));

        URI searchUri = buildApiUri(Map.of(
                "action", "query",
                "format", "json",
                "formatversion", "2",
                "list", "search",
                "srnamespace", "0",
                "srlimit", Integer.toString(Math.max(limit, 8)),
                "srwhat", "text",
                "srsearch", query
        ));

        CompletableFuture<java.util.List<SearchResult>> prefix = sendJson(prefixUri)
                .thenApply(root -> parseSearchResults(root, "prefixsearch"))
                .exceptionally(ignored -> java.util.List.<SearchResult>of());
        CompletableFuture<java.util.List<SearchResult>> full = sendJson(searchUri)
                .thenApply(root -> parseSearchResults(root, "search"))
                .exceptionally(ignored -> java.util.List.<SearchResult>of());

        return exactTitle.thenCombine(itemIdCandidate, (exact, ids) -> {
            java.util.ArrayList<SearchResult> all = new java.util.ArrayList<>();
            all.addAll(exact);
            all.addAll(ids);
            return all;
        }).thenCombine(prefix, (all, prefixes) -> {
            all.addAll(prefixes);
            return all;
        }).thenCombine(full, (all, fullResults) -> {
            all.addAll(fullResults);

            java.util.LinkedHashMap<String, SearchResult> unique = new java.util.LinkedHashMap<>();
            all.stream()
                    .sorted(java.util.Comparator
                            .comparingInt((SearchResult result) -> searchScore(query, result.title()))
                            .reversed()
                            .thenComparing(SearchResult::title, String.CASE_INSENSITIVE_ORDER))
                    .forEach(result -> unique.putIfAbsent(
                            result.title().toLowerCase(Locale.ROOT),
                            result
                    ));

            return unique.values().stream().limit(limit).toList();
        });
    }

    private static String normalizeItemIdQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        return normalized.matches("[A-Z0-9_]{3,}") && normalized.contains("_")
                ? normalized
                : "";
    }

    private static int searchScore(String query, String title) {
        String needle = normalizeSearchText(query);
        String candidate = normalizeSearchText(title);
        if (candidate.equals(needle)) {
            return 10_000;
        }
        int score = 0;
        if (candidate.startsWith(needle)) {
            score += 4_000;
        }
        if (candidate.contains(needle)) {
            score += 2_000;
        }
        String[] words = needle.split(" ");
        for (String word : words) {
            if (!word.isBlank() && candidate.contains(word)) {
                score += 200;
            }
        }
        score -= Math.abs(candidate.length() - needle.length());
        return score;
    }

    private static String normalizeSearchText(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\s+", " ")
                .trim();
    }

    private static java.util.List<SearchResult> parseSearchResults(JsonObject root, String property) {
        JsonObject query = getObject(root, "query");
        JsonArray array = query == null ? null : getArray(query, property);
        if (array == null) {
            return java.util.List.of();
        }
        java.util.List<SearchResult> results = new java.util.ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            String title = getString(element.getAsJsonObject(), "title");
            if (!title.isBlank()) results.add(new SearchResult(title, buildArticleUri(title)));
        }
        return java.util.List.copyOf(results);
    }

    private static CompletableFuture<ResolvedWikiTitle> resolveCandidate(java.util.List<String> candidates, int index) {
        if (index >= candidates.size()) {
            return CompletableFuture.failedFuture(new WikiResolutionException("No exact Wiki page matched the item"));
        }
        return resolveExact(candidates.get(index)).thenCompose(result -> {
            if (result != null) {
                return CompletableFuture.completedFuture(result);
            }
            return resolveCandidate(candidates, index + 1);
        });
    }

    private static CompletableFuture<ResolvedWikiTitle> resolveExact(String title) {
        URI uri = buildApiUri(Map.of(
                "action", "query",
                "format", "json",
                "formatversion", "2",
                "redirects", "1",
                "prop", "info",
                "inprop", "url",
                "titles", title
        ));
        return sendJson(uri).thenApply(root -> {
            JsonObject query = getObject(root, "query");
            JsonArray pages = query == null ? null : getArray(query, "pages");
            if (pages == null || pages.isEmpty() || !pages.get(0).isJsonObject()) {
                return null;
            }
            JsonObject page = pages.get(0).getAsJsonObject();
            if (page.has("missing") || page.has("invalid") || getInt(page, "ns", -1) != 0) {
                return null;
            }
            String canonicalTitle = getString(page, "title");
            if (canonicalTitle.isBlank()) {
                return null;
            }
            String fullUrl = getString(page, "fullurl");
            URI pageUri = fullUrl.isBlank() ? buildArticleUri(canonicalTitle) : URI.create(fullUrl);
            return new ResolvedWikiTitle(canonicalTitle, pageUri);
        });
    }

    private static CompletableFuture<Void> ensureRegistryLoaded() {
        long now = System.currentTimeMillis();
        if (!ITEM_NAMES.isEmpty() && now - registryLoadedAt < REGISTRY_TTL_MILLIS) {
            return CompletableFuture.completedFuture(null);
        }
        synchronized (WikiTitleResolver.class) {
            now = System.currentTimeMillis();
            if (!ITEM_NAMES.isEmpty() && now - registryLoadedAt < REGISTRY_TTL_MILLIS) {
                return CompletableFuture.completedFuture(null);
            }
            if (registryLoad != null && !registryLoad.isDone()) {
                return registryLoad;
            }
            registryLoad = loadRegistry();
            return registryLoad;
        }
    }

    private static CompletableFuture<Void> loadRegistry() {
        HttpRequest request = requestBuilder(URI.create(HYPIXEL_ITEMS_ENDPOINT))
                .header("Accept", "application/json")
                .GET()
                .build();
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(WikiTitleResolver::validateResponse)
                .thenAccept(body -> {
                    JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                    JsonArray items = getArray(root, "items");
                    if (items == null) {
                        throw new WikiResolutionException("Hypixel item registry contained no items array");
                    }
                    Map<String, String> updated = new ConcurrentHashMap<>();
                    for (JsonElement element : items) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        JsonObject item = element.getAsJsonObject();
                        String id = getString(item, "id");
                        String name = stripFormatting(getString(item, "name"));
                        if (!id.isBlank() && !name.isBlank()) {
                            updated.put(id.toUpperCase(Locale.ROOT), name);
                        }
                    }
                    if (updated.isEmpty()) {
                        throw new WikiResolutionException("Hypixel item registry was empty");
                    }
                    ITEM_NAMES.clear();
                    ITEM_NAMES.putAll(updated);
                    registryLoadedAt = System.currentTimeMillis();
                });
    }

    private static CompletableFuture<JsonObject> sendJson(URI uri) {
        HttpRequest request = requestBuilder(uri)
                .header("Accept", "application/json")
                .GET()
                .build();
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(WikiTitleResolver::validateResponse)
                .thenApply(body -> JsonParser.parseString(body).getAsJsonObject());
    }

    private static HttpRequest.Builder requestBuilder(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "NSM-Mod/1.0 Hypixel-SkyBlock-Wiki-Reader")
                .header("Accept-Language", "en-US,en;q=0.9");
    }

    private static String validateResponse(HttpResponse<String> response) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new WikiResolutionException("HTTP " + response.statusCode() + " from " + response.uri());
        }
        if (response.body() == null || response.body().isBlank()) {
            throw new WikiResolutionException("Empty response from " + response.uri());
        }
        return response.body();
    }

    private static URI buildApiUri(Map<String, String> parameters) {
        StringBuilder result = new StringBuilder(WIKI_API_ENDPOINT).append('?');
        boolean first = true;
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (!first) {
                result.append('&');
            }
            first = false;
            result.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return URI.create(result.toString());
    }

    private static URI buildArticleUri(String title) {
        return URI.create(WIKI_ARTICLE_BASE + encode(title).replace("+", "%20"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String stripFormatting(String input) {
        return input == null ? "" : input.replaceAll("(?i)\\u00a7[0-9A-FK-ORX]", "").replaceAll("\\s+", " ").trim();
    }

    private static JsonObject getObject(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonObject()
                ? parent.getAsJsonObject(name)
                : null;
    }

    private static JsonArray getArray(JsonObject parent, String name) {
        return parent != null && parent.has(name) && parent.get(name).isJsonArray()
                ? parent.getAsJsonArray(name)
                : null;
    }

    private static String getString(JsonObject object, String name) {
        try {
            return object != null && object.has(name) && !object.get(name).isJsonNull()
                    ? object.get(name).getAsString()
                    : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int getInt(JsonObject object, String name, int fallback) {
        try {
            return object != null && object.has(name) && !object.get(name).isJsonNull()
                    ? object.get(name).getAsInt()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public record SearchResult(String title, URI pageUri) { }

    public record ResolvedWikiTitle(String title, URI pageUri) { }

    private static final class WikiResolutionException extends RuntimeException {
        private WikiResolutionException(String message) {
            super(message);
        }
    }
}
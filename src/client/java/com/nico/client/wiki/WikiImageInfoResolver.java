package com.nico.client.wiki;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class WikiImageInfoResolver {
    private static final String API = "https://hypixelskyblock.minecraft.wiki/api.php";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Map<String, CompletableFuture<ResolvedImage>> CACHE = new ConcurrentHashMap<>();

    private WikiImageInfoResolver() { }

    static CompletableFuture<ResolvedImage> resolve(WikiImage image) {
        if (image == null || image.isEmpty()) {
            return CompletableFuture.completedFuture(ResolvedImage.empty());
        }

        String fileTitle = extractFileTitle(image);
        if (fileTitle.isBlank()) {
            return CompletableFuture.completedFuture(new ResolvedImage(
                    image.url(), "", image.declaredWidth(), image.declaredHeight()
            ));
        }

        String normalizedTitle = fileTitle.regionMatches(true, 0, "File:", 0, 5)
                ? fileTitle
                : "File:" + fileTitle;

        return CACHE.computeIfAbsent(normalizedTitle.toLowerCase(Locale.ROOT), ignored -> query(normalizedTitle)
                .exceptionally(throwable -> new ResolvedImage(
                        image.url(), "", image.declaredWidth(), image.declaredHeight()
                )));
    }

    private static CompletableFuture<ResolvedImage> query(String fileTitle) {
        String uri = API
                + "?action=query"
                + "&format=json"
                + "&formatversion=2"
                + "&prop=imageinfo"
                + "&iiprop=url%7Cmime%7Csize"
                + "&titles=" + URLEncoder.encode(fileTitle, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "NSM-Mod/1.0 Hypixel-SkyBlock-Wiki-Reader")
                .header("Accept", "application/json")
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("imageinfo HTTP " + response.statusCode());
                    }
                    return response.body();
                })
                .thenApply(WikiImageInfoResolver::parse);
    }

    private static ResolvedImage parse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject query = root.has("query") && root.get("query").isJsonObject()
                ? root.getAsJsonObject("query")
                : null;
        JsonArray pages = query != null && query.has("pages") && query.get("pages").isJsonArray()
                ? query.getAsJsonArray("pages")
                : null;

        if (pages == null || pages.isEmpty()) {
            throw new IllegalStateException("imageinfo returned no pages");
        }

        JsonObject page = pages.get(0).getAsJsonObject();
        if (page.has("missing") || !page.has("imageinfo") || !page.get("imageinfo").isJsonArray()) {
            throw new IllegalStateException("imageinfo file is missing");
        }

        JsonArray imageInfo = page.getAsJsonArray("imageinfo");
        if (imageInfo.isEmpty()) {
            throw new IllegalStateException("imageinfo returned no image record");
        }

        JsonObject info = imageInfo.get(0).getAsJsonObject();
        String url = string(info, "url");
        if (url.isBlank()) {
            throw new IllegalStateException("imageinfo returned no original URL");
        }

        return new ResolvedImage(url, string(info, "mime"), integer(info, "width"), integer(info, "height"));
    }

    private static String extractFileTitle(WikiImage image) {
        for (String candidate : new String[]{image.altText(), image.title()}) {
            String normalized = normalizeFileName(candidate);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }

        String url = image.url();
        if (url == null || url.isBlank()) {
            return "";
        }

        try {
            String path = URI.create(url).getPath();
            String marker = "/Special:Redirect/file/";
            int markerIndex = path.indexOf(marker);
            if (markerIndex >= 0) {
                return normalizeFileName(URLDecoder.decode(
                        path.substring(markerIndex + marker.length()), StandardCharsets.UTF_8
                ));
            }

            String lastSegment = path.substring(path.lastIndexOf('/') + 1);
            return normalizeFileName(URLDecoder.decode(lastSegment, StandardCharsets.UTF_8));
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String normalizeFileName(String value) {
        if (value == null) {
            return "";
        }
        String result = value.trim();
        if (result.regionMatches(true, 0, "File:", 0, 5)) {
            result = result.substring(5).trim();
        }
        if (result.regionMatches(true, 0, "Image:", 0, 6)) {
            result = result.substring(6).trim();
        }
        return result.matches("(?i).+\\.(png|jpe?g|gif|bmp|webp)$") ? result : "";
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return "";
        }
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static int integer(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return 0;
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    record ResolvedImage(String url, String mime, int width, int height) {
        ResolvedImage {
            url = url == null ? "" : url.trim();
            mime = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
            width = Math.max(0, width);
            height = Math.max(0, height);
        }

        static ResolvedImage empty() {
            return new ResolvedImage("", "", 0, 0);
        }
    }
}
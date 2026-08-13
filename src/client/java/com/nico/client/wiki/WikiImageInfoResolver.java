package com.nico.client.wiki;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

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

/** Resolves a MediaWiki file title, original URL, and file-specific credits. */
final class WikiImageInfoResolver {
    private static final String API = "https://hypixelskyblock.minecraft.wiki/api.php";
    private static final String FILE_PAGE_BASE = "https://hypixelskyblock.minecraft.wiki/w/";
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
            WikiImageCredits credits = new WikiImageCredits(
                    "",
                    "",
                    image.url(),
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    false
            );
            return CompletableFuture.completedFuture(new ResolvedImage(
                    image.url(), "", image.declaredWidth(), image.declaredHeight(), credits
            ));
        }

        String normalizedTitle = fileTitle.regionMatches(true, 0, "File:", 0, 5)
                ? fileTitle
                : "File:" + fileTitle;

        return CACHE.computeIfAbsent(normalizedTitle.toLowerCase(Locale.ROOT), ignored -> query(normalizedTitle)
                .exceptionally(throwable -> fallback(image, normalizedTitle)));
    }

    private static CompletableFuture<ResolvedImage> query(String fileTitle) {
        String uri = API
                + "?action=query"
                + "&format=json"
                + "&formatversion=2"
                + "&prop=imageinfo"
                + "&iiprop=url%7Cmime%7Csize%7Cextmetadata"
                + "&titles=" + URLEncoder.encode(fileTitle, StandardCharsets.UTF_8);

        HttpRequest request = WikiHttp.request(URI.create(uri), Duration.ofSeconds(20))
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
                .thenApply(body -> parse(body, fileTitle));
    }

    private static ResolvedImage parse(String body, String requestedTitle) {
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

        String fileTitle = string(page, "title");
        if (fileTitle.isBlank()) {
            fileTitle = requestedTitle;
        }
        String filePageUrl = string(info, "descriptionurl");
        if (filePageUrl.isBlank()) {
            filePageUrl = filePageUrl(fileTitle);
        }

        JsonObject metadata = info.has("extmetadata") && info.get("extmetadata").isJsonObject()
                ? info.getAsJsonObject("extmetadata")
                : null;

        WikiImageCredits credits = new WikiImageCredits(
                fileTitle,
                filePageUrl,
                url,
                metadataText(metadata, "LicenseShortName"),
                metadataUrl(metadata, "LicenseUrl"),
                metadataText(metadata, "Artist"),
                metadataText(metadata, "Credit"),
                metadataText(metadata, "UsageTerms"),
                metadataText(metadata, "Attribution"),
                metadataText(metadata, "Source"),
                metadata != null
        );

        return new ResolvedImage(
                url,
                string(info, "mime"),
                integer(info, "width"),
                integer(info, "height"),
                credits
        );
    }

    private static ResolvedImage fallback(WikiImage image, String fileTitle) {
        WikiImageCredits credits = new WikiImageCredits(
                fileTitle,
                filePageUrl(fileTitle),
                image.url(),
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                false
        );
        return new ResolvedImage(
                image.url(), "", image.declaredWidth(), image.declaredHeight(), credits
        );
    }

    private static String extractFileTitle(WikiImage image) {
        /*
         * Prefer the URL over alt/title. MediaWiki alt text is often a human
         * label (and can even refer to a different animated frame), while the
         * thumbnail URL contains the canonical file name needed by imageinfo.
         */
        String fromUrl = extractFileTitleFromUrl(image.url());
        if (!fromUrl.isBlank()) {
            return fromUrl;
        }

        for (String candidate : new String[]{image.title(), image.altText()}) {
            String normalized = normalizeFileName(candidate);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private static String extractFileTitleFromUrl(String url) {
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

            String[] rawSegments = path.split("/");

            /*
             * MediaWiki thumbnail URLs normally look like:
             *   .../thumb/a/ab/File.png/80px-File.png
             * The directory immediately before the sized thumbnail is the
             * canonical original filename.
             */
            for (String rawSegment : rawSegments) {
                if (!rawSegment.equalsIgnoreCase("thumb")) {
                    continue;
                }
                if (rawSegments.length >= 2) {
                    String beforeThumbnail = URLDecoder.decode(
                            rawSegments[rawSegments.length - 2], StandardCharsets.UTF_8
                    );
                    String normalized = normalizeFileName(beforeThumbnail);
                    if (!normalized.isBlank()) {
                        return normalized;
                    }
                }
            }

            for (int index = rawSegments.length - 1; index >= 0; index--) {
                String segment = URLDecoder.decode(rawSegments[index], StandardCharsets.UTF_8);
                String normalized = normalizeFileName(segment);
                if (!normalized.isBlank()) {
                    return normalized;
                }
            }
            return "";
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

        // Strip MediaWiki thumbnail size prefixes such as 40px- or 1.5x-.
        result = result.replaceFirst("(?i)^\\d+(?:\\.\\d+)?(?:px|x)-", "");

        // Some thumbnail endpoints append .webp to the original extension.
        if (result.matches("(?i).+\\.(png|jpe?g|gif|bmp)\\.webp$")) {
            result = result.substring(0, result.length() - ".webp".length());
        }

        return result.matches("(?i).+\\.(png|jpe?g|gif|bmp|webp)$") ? result : "";
    }

    private static String metadataText(JsonObject metadata, String key) {
        String raw = metadataValue(metadata, key);
        if (raw.isBlank()) {
            return "";
        }
        return Jsoup.parseBodyFragment(raw).text().replaceAll("\\s+", " ").trim();
    }

    private static String metadataUrl(JsonObject metadata, String key) {
        String raw = metadataValue(metadata, key);
        if (raw.isBlank()) {
            return "";
        }
        Document document = Jsoup.parseBodyFragment(raw);
        Element anchor = document.selectFirst("a[href]");
        String candidate = anchor == null ? document.text() : anchor.attr("href");
        candidate = candidate == null ? "" : candidate.trim();
        return candidate.startsWith("//") ? "https:" + candidate : candidate;
    }

    private static String metadataValue(JsonObject metadata, String key) {
        if (metadata == null || !metadata.has(key) || !metadata.get(key).isJsonObject()) {
            return "";
        }
        return string(metadata.getAsJsonObject(key), "value");
    }

    private static String filePageUrl(String fileTitle) {
        if (fileTitle == null || fileTitle.isBlank()) {
            return "";
        }
        String encoded = URLEncoder.encode(fileTitle.replace(' ', '_'), StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
        return FILE_PAGE_BASE + encoded;
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

    record ResolvedImage(String url, String mime, int width, int height, WikiImageCredits credits) {
        ResolvedImage {
            url = url == null ? "" : url.trim();
            mime = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
            width = Math.max(0, width);
            height = Math.max(0, height);
            credits = credits == null ? WikiImageCredits.empty() : credits;
        }

        static ResolvedImage empty() {
            return new ResolvedImage("", "", 0, 0, WikiImageCredits.empty());
        }
    }
}

package com.nico.client.utils.tradeprot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nico.client.wiki.WikiHttp;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

public final class AuctionHouseService {
    private static final String AUCTIONS_ENDPOINT = "https://api.hypixel.net/v2/skyblock/auctions";
    private static final long CACHE_TIL_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final int MAX_PAGES = 100;
    private static final int MAX_WORKERS = 6;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile Snapshot cachedSnapshot = Snapshot.empty();
    private static volatile long cachedAt;
    private static volatile CompletableFuture<Snapshot> refresh;

    private AuctionHouseService() { }

    public static CompletableFuture<LowestBin> lowestBin(String internalId, String displayName) {
        String wantedId = normalizeId(internalId);
        String wantedName = normalizeName(displayName);
        if (wantedId.isBlank() && wantedName.isBlank()) {
            return CompletableFuture.completedFuture(LowestBin.unavailable("No item identity was available"));
        }

        return snapshot()
                .thenApply(snapshot -> {
                    Long value = wantedId.isBlank() ? null : snapshot.byItemId().get(wantedId);
                    if (value == null && !wantedName.isBlank()) {
                        value = findByName(snapshot.byNormalizedName(), wantedName);
                    }
                    if (value == null || value <= 0L) {
                        return LowestBin.unavailable("No active BIN listing was found");
                    }
                    return new LowestBin(value, snapshot.lastUpdated(), "");
                })
                .exceptionally(throwable -> LowestBin.unavailable(rootMessage(throwable)));
    }

    public static synchronized void invalidate() {
        cachedSnapshot = Snapshot.empty();
        cachedAt = 0;
        refresh = null;
    }

    private static CompletableFuture<Snapshot> snapshot() {
        long now = System.currentTimeMillis();
        Snapshot current = cachedSnapshot;
        if (!current.isEmpty() && now - cachedAt < CACHE_TIL_MILLIS) {
            return CompletableFuture.completedFuture(current);
        }

        synchronized (AuctionHouseService.class) {
            now = System.currentTimeMillis();
            current = cachedSnapshot;
            if (!current.isEmpty() && now - cachedAt < CACHE_TIL_MILLIS) {
                return CompletableFuture.completedFuture(current);
            }
            if (refresh != null && !refresh.isDone()) return refresh;

            refresh = loadSnapshot().whenComplete((loaded, throwable) -> {
                synchronized (AuctionHouseService.class) {
                    if (throwable == null && loaded != null && !loaded.isEmpty()) {
                        cachedSnapshot = loaded;
                        cachedAt = System.currentTimeMillis();
                    }
                    refresh = null;
                }
            });
            return refresh;
        }
    }

    private static CompletableFuture<Snapshot> loadSnapshot() {
        return fetchPage(0).thenCompose(firstPage -> {
            ConcurrentHashMap<String, Long> byItemId = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, Long> byName = new ConcurrentHashMap<>();
            indexPage(firstPage, byItemId, byName);

            int totalPages = Math.max(1, Math.min(MAX_PAGES, firstPage.totalPages));
            if (totalPages <= 1) {
                return CompletableFuture.completedFuture(new Snapshot(
                        firstPage.lastUpdated, Map.copyOf(byItemId), Map.copyOf(byName)
                ));
            }

            AtomicInteger nextPage = new AtomicInteger(1);
            int workerCount = Math.min(MAX_WORKERS, totalPages - 1);
            CompletableFuture<?>[] workers = new CompletableFuture<?>[workerCount];
            for (int index = 0; index < workerCount; index++) {
                workers[index] = loadWorker(nextPage, totalPages, byItemId, byName);
            }

            return CompletableFuture.allOf(workers).thenApply(ignored -> new Snapshot(
                    firstPage.lastUpdated(), Map.copyOf(byItemId), Map.copyOf(byName)
            ));
        });
    }

    private static CompletableFuture<Void> loadWorker(
            AtomicInteger nextPage,
            int totalPages,
            ConcurrentHashMap<String, Long> byItemId,
            ConcurrentHashMap<String, Long> byName
    ) {
        int page = nextPage.getAndIncrement();
        if (page >= totalPages) {
            return CompletableFuture.completedFuture(null);
        }

        return fetchPage(page).thenCompose(result -> {
            indexPage(result, byItemId, byName);
            return loadWorker(nextPage, totalPages, byItemId, byName);
        });
    }

    private static CompletableFuture<AuctionPage> fetchPage(int page) {
        URI uri = URI.create(AUCTIONS_ENDPOINT + "?page="
                + URLEncoder.encode(Integer.toString(page), StandardCharsets.UTF_8));
        HttpRequest request = WikiHttp.request(uri, Duration.ofSeconds(25))
                .header("Accept", "application/json")
                .GET()
                .build();

        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if ((response.statusCode() == 404 || response.statusCode() == 422) && page > 0) {
                        // The auction set can roll over while pages are being fetched.
                        // Treat a vanished tail page as empty instead of discarding the
                        // complete snapshot that was already collected.
                        return new AuctionPage(page + 1, 0L, new JsonArray());
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new IllegalStateException("Hypixel auctions HTTP " + response.statusCode());
                    }
                    return parsePage(response.body());
                });
    }

    private static AuctionPage parsePage(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!booleanValue(root, "success", false)) {
            throw new IllegalStateException("Hypixel auctions response was not successful");
        }

        int totalPages = intValue(root, "totalPages", 1);
        long lastUpdated = longValue(root, "lastUpdated", 0L);
        JsonArray auctions = root.has("auctions") && root.get("auctions").isJsonArray()
                ? root.getAsJsonArray("auctions")
                : new JsonArray();
        return new AuctionPage(totalPages, lastUpdated, auctions);
    }

    private static void indexPage(
            AuctionPage page,
            ConcurrentHashMap<String, Long> byItemId,
            ConcurrentHashMap<String, Long> byName
    ) {
        for (JsonElement element : page.auctions()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject auction = element.getAsJsonObject();
            if (!booleanValue(auction, "bin", false)) {
                continue;
            }

            long price = longValue(auction, "starting_bid", 0L);
            if (price <= 0L) {
                continue;
            }

            String normalizedName = normalizeName(stringValue(auction, "item_name"));
            if (!normalizedName.isBlank()) {
                byName.merge(normalizedName, price, Math::min);
            }

            String encoded = itemBytesData(auction.get("item_bytes"));
            String itemId = NbtItemIdReader.readExtraAttributesId(encoded);
            if (!itemId.isBlank()) {
                byItemId.merge(normalizeId(itemId), price, Math::min);
            }
        }
    }

    private static String itemBytesData(JsonElement itemBytes) {
        if (itemBytes == null || itemBytes.isJsonNull()) {
            return "";
        }
        try {
            if (itemBytes.isJsonPrimitive()) {
                return itemBytes.getAsString();
            }
            if (itemBytes.isJsonObject()) {
                return stringValue(itemBytes.getAsJsonObject(), "data");
            }
        } catch (RuntimeException ignored) {
            // Invalid auction item_bytes; ignore this auction's ID.
        }
        return "";
    }

    private static Long findByName(Map<String, Long> byName, String wantedName) {
        Long exact = byName.get(wantedName);
        if (exact != null) {
            return exact;
        }

        Long result = null;
        for (Map.Entry<String, Long> entry : byName.entrySet()) {
            String candidate = entry.getKey();
            // Reforges are prefixes; stars and decorative suffixes are removed
            // by normalizeName. A complete-name suffix is therefore a safe
            // fallback when NBT extraction was unavailable for a listing.
            if (!candidate.endsWith(" " + wantedName)) {
                continue;
            }
            result = result == null ? entry.getValue() : Math.min(result, entry.getValue());
        }
        return result;
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replaceAll("(?i)§[0-9A-FK-ORX]", "")
                .replaceAll("[✪★☆]+", " ")
                .replaceAll("[➊➋➌➍➎]+", " ")
                .toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        if (current == null || current.getMessage() == null || current.getMessage().isBlank()) {
            return "Auction data unavailable";
        }
        return current.getMessage();
    }

    private static String stringValue(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString()
                    : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsLong()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsInt()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsBoolean()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public record LowestBin(long coins, long sourceLastUpdated, String error) {
        public LowestBin {
            coins = Math.max(0L, coins);
            sourceLastUpdated = Math.max(0L, sourceLastUpdated);
            error = error == null ? "" : error.trim();
        }

        public boolean available() {
            return coins > 0L;
        }

        static LowestBin unavailable(String error) {
            return new LowestBin(0L, 0L, error);
        }
    }

    private record Snapshot(long lastUpdated, Map<String, Long> byItemId, Map<String, Long> byNormalizedName) {
        private Snapshot {
            lastUpdated = Math.max(0L, lastUpdated);
            byItemId = byItemId == null ? Map.of() : Map.copyOf(byItemId);
            byNormalizedName = byNormalizedName == null ? Map.of() : Map.copyOf(byNormalizedName);
        }

        static Snapshot empty() {
            return new Snapshot(0L, Map.of(), Map.of());
        }

        boolean isEmpty() {
            return byItemId.isEmpty() && byNormalizedName.isEmpty();
        }
    }

    private record AuctionPage(int totalPages, long lastUpdated, JsonArray auctions) {
        private AuctionPage {
            totalPages = Math.max(1, totalPages);
            lastUpdated = Math.max(0L, lastUpdated);
            auctions = auctions == null ? new JsonArray() : auctions;
        }
    }

    /** Minimal NBT reader that searches for ExtraAttributes.id without Minecraft internals. */
    private static final class NbtItemIdReader {
        private static final int MAX_DEPTH = 64;
        private static final int MAX_ARRAY_LENGTH = 16 * 1024 * 1024;

        private NbtItemIdReader() { }

        static String readExtraAttributesId(String base64) {
            if (base64 == null || base64.isBlank()) {
                return "";
            }

            try {
                byte[] compressed = Base64.getDecoder().decode(base64);
                if (compressed.length == 0) {
                    return "";
                }

                InputStream raw = new ByteArrayInputStream(compressed);
                InputStream decoded = compressed.length >= 2
                        && (compressed[0] & 0xFF) == 0x1F
                        && (compressed[1] & 0xFF) == 0x8B
                        ? new GZIPInputStream(raw)
                        : raw;

                try (DataInputStream input = new DataInputStream(decoded)) {
                    int rootType = input.readUnsignedByte();
                    if (rootType == 0) {
                        return "";
                    }
                    readString(input); // root tag name
                    String result = readPayload(input, rootType, 0, false);
                    return result == null ? "" : result.trim();
                }
            } catch (IOException | IllegalArgumentException ignored) {
                return "";
            }
        }

        private static String readPayload(
                DataInputStream input,
                int type,
                int depth,
                boolean insideExtraAttributes
        ) throws IOException {
            if (depth > MAX_DEPTH) {
                throw new IOException("NBT nesting is too deep");
            }

            return switch (type) {
                case 0 -> "";
                case 1 -> { input.readByte(); yield ""; }
                case 2 -> { input.readShort(); yield ""; }
                case 3 -> { input.readInt(); yield ""; }
                case 4 -> { input.readLong(); yield ""; }
                case 5 -> { input.readFloat(); yield ""; }
                case 6 -> { input.readDouble(); yield ""; }
                case 7 -> { skipBytes(input, checkedLength(input.readInt())); yield ""; }
                case 8 -> { readString(input); yield ""; }
                case 9 -> readList(input, depth, insideExtraAttributes);
                case 10 -> readCompound(input, depth, insideExtraAttributes);
                case 11 -> { skipBytes(input, Math.multiplyExact(checkedLength(input.readInt()), 4)); yield ""; }
                case 12 -> { skipBytes(input, Math.multiplyExact(checkedLength(input.readInt()), 8)); yield ""; }
                default -> throw new IOException("Unsupported NBT tag type " + type);
            };
        }

        private static String readList(
                DataInputStream input,
                int depth,
                boolean insideExtraAttributes
        ) throws IOException {
            int elementType = input.readUnsignedByte();
            int length = checkedLength(input.readInt());
            for (int index = 0; index < length; index++) {
                String result = readPayload(input, elementType, depth + 1, insideExtraAttributes);
                if (!result.isBlank()) {
                    return result;
                }
            }
            return "";
        }

        private static String readCompound(
                DataInputStream input,
                int depth,
                boolean insideExtraAttributes
        ) throws IOException {
            while (true) {
                int childType = input.readUnsignedByte();
                if (childType == 0) {
                    return "";
                }

                String childName = readString(input);
                boolean childInsideExtra = insideExtraAttributes
                        || childName.equalsIgnoreCase("ExtraAttributes");

                if (insideExtraAttributes && childType == 8 && childName.equals("id")) {
                    return readString(input);
                }

                String result = readPayload(input, childType, depth + 1, childInsideExtra);
                if (!result.isBlank()) {
                    return result;
                }
            }
        }

        private static String readString(DataInputStream input) throws IOException {
            int length = input.readUnsignedShort();
            if (length == 0) {
                return "";
            }
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) {
                throw new IOException("Unexpected end of NBT string");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private static int checkedLength(int length) throws IOException {
            if (length < 0 || length > MAX_ARRAY_LENGTH) {
                throw new IOException("Invalid NBT array/list length " + length);
            }
            return length;
        }

        private static void skipBytes(DataInputStream input, int length) throws IOException {
            if (length < 0 || length > MAX_ARRAY_LENGTH * 8L) {
                throw new IOException("Invalid NBT byte length " + length);
            }
            input.skipNBytes(length);
        }
    }
}

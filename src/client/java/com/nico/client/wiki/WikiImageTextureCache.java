package com.nico.client.wiki;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Downloads original wiki files asynchronously and registers them as dynamic textures. */
public final class WikiImageTextureCache {
    private static final int MAX_DOWNLOAD_BYTES = 12 * 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;
    private static final long RETRY_AFTER_MILLIS = Duration.ofSeconds(30).toMillis();

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    private WikiImageTextureCache() { }

    public static Snapshot request(WikiImage image) {
        if (image == null || image.isEmpty()) {
            return Snapshot.empty();
        }
        String key = image.url() + "|" + image.altText() + "|" + image.title();
        Entry entry = CACHE.computeIfAbsent(key, ignored -> new Entry(key, image));
        entry.startIfNeeded();
        return entry.snapshot();
    }

    public static Snapshot request(String url) {
        return request(new WikiImage(url, "", "", 0, 0));
    }

    public static void invalidate(String key) {
        Entry entry = CACHE.remove(key);
        if (entry != null) {
            entry.releaseTexture();
        }
    }

    public static void clear() {
        ArrayList<Entry> entries = new ArrayList<>(CACHE.values());
        CACHE.clear();
        for (Entry entry : entries) {
            entry.releaseTexture();
        }
    }

    private static Identifier textureId(String key) {
        return Identifier.fromNamespaceAndPath("nsm", "wiki/" + sha256Hex(key));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(Character.forDigit((b >>> 4) & 0xF, 16));
                result.append(Character.forDigit(b & 0xF, 16));
            }
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public enum Status { EMPTY, LOADING, READY, FAILED }

    public record Snapshot(
            Status status,
            Identifier textureId,
            int width,
            int contentX,
            int contentY,
            int contentWidth,
            int contentHeight,
            int height,
            String error,
            WikiImageCredits credits
    ) {
        public Snapshot {
            status = status == null ? Status.EMPTY : status;
            width = Math.max(0, width);
            height = Math.max(0, height);
            contentX = Math.max(0, contentX);
            contentY = Math.max(0, contentY);
            contentWidth = Math.max(0, contentWidth);
            contentHeight = Math.max(0, contentHeight);
            error = error == null ? "" : error;
            credits = credits == null ? WikiImageCredits.empty() : credits;
        }

        public static Snapshot empty() {
            return new Snapshot(Status.EMPTY, null, 0, 0, 0, 0, 0, 0, "", WikiImageCredits.empty());
        }

        public boolean ready() {
            return status == Status.READY && textureId != null && width > 0 && height > 0;
        }
    }

    private static final class Entry {
        private final String key;
        private final WikiImage source;
        private volatile Status status = Status.EMPTY;
        private volatile Identifier identifier;
        private volatile int width;
        private volatile int height;
        private volatile int contentX;
        private volatile int contentY;
        private volatile int contentWidth;
        private volatile int contentHeight;
        private volatile String error = "";
        private volatile WikiImageCredits credits = WikiImageCredits.empty();
        private volatile long failedAt;

        private Entry(String key, WikiImage source) {
            this.key = key;
            this.source = source;
        }

        private synchronized void startIfNeeded() {
            if (status == Status.LOADING || status == Status.READY) {
                return;
            }
            if (status == Status.FAILED && System.currentTimeMillis() - failedAt < RETRY_AFTER_MILLIS) {
                return;
            }

            status = Status.LOADING;
            error = "";

            WikiImageInfoResolver.resolve(source).whenComplete((resolved, resolveFailure) -> {
                if (resolved != null) {
                    credits = resolved.credits();
                }
                if (resolveFailure != null || resolved == null || resolved.url().isBlank()) {
                    fail(resolveFailure == null ? "Could not resolve original wiki image URL" : resolveFailure.getMessage());
                    return;
                }
                download(resolved);
            });
        }

        private void download(WikiImageInfoResolver.ResolvedImage resolved) {
            HttpRequest request;
            try {
                request = WikiHttp.request(URI.create(resolved.url()), Duration.ofSeconds(20))
                        .header("Referer", WikiAttribution.WIKI_HOME.toString())
                        .header("Accept", "image/png,image/jpeg,image/gif,image/bmp;q=0.9,*/*;q=0.1")
                        .GET()
                        .build();
            } catch (IllegalArgumentException exception) {
                fail("Invalid resolved image URL");
                return;
            }

            HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                    .whenComplete((response, throwable) -> {
                        if (throwable != null) {
                            fail(throwable.getMessage());
                            return;
                        }
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            fail("HTTP " + response.statusCode());
                            return;
                        }

                        String contentType = response.headers().firstValue("Content-Type")
                                .orElse(resolved.mime()).toLowerCase(Locale.ROOT);
                        byte[] bytes = response.body();
                        if (bytes == null || bytes.length == 0) {
                            fail("Empty image response");
                            return;
                        }
                        if (bytes.length > MAX_DOWNLOAD_BYTES) {
                            fail("Image exceeds " + MAX_DOWNLOAD_BYTES + " bytes");
                            return;
                        }

                        NativeImage image;
                        try {
                            image = decode(bytes, contentType);
                        } catch (Exception exception) {
                            fail("Could not decode " + contentType + ": " + exception.getMessage());
                            return;
                        }

                        int decodedWidth = image.getWidth();
                        int decodedHeight = image.getHeight();
                        long pixels = (long) decodedWidth * decodedHeight;
                        if (decodedWidth <= 0 || decodedHeight <= 0
                                || decodedWidth > MAX_DIMENSION || decodedHeight > MAX_DIMENSION
                                || pixels > (long) MAX_DIMENSION * MAX_DIMENSION) {
                            image.close();
                            fail("Unsupported image dimensions " + decodedWidth + "x" + decodedHeight);
                            return;
                        }

                        Minecraft.getInstance().execute(() -> register(image, decodedWidth, decodedHeight));
                    });
        }

        private static NativeImage decode(byte[] bytes, String contentType) throws Exception {
            if (isPng(bytes) || contentType.contains("png")) {
                return NativeImage.read(bytes);
            }

            BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(bytes));
            if (buffered == null) {
                throw new IllegalArgumentException("unsupported image format");
            }

            NativeImage nativeImage = new NativeImage(buffered.getWidth(), buffered.getHeight(), true);
            for (int y = 0; y < buffered.getHeight(); y++) {
                for (int x = 0; x < buffered.getWidth(); x++) {
                    int argb = buffered.getRGB(x, y);
                    int abgr = (argb & 0xFF00FF00)
                            | ((argb & 0x00FF0000) >>> 16)
                            | ((argb & 0x000000FF) << 16);
                    nativeImage.setPixelABGR(x, y, abgr);
                }
            }
            return nativeImage;
        }

        private static boolean isPng(byte[] bytes) {
            return bytes.length >= 8
                    && (bytes[0] & 0xFF) == 0x89
                    && bytes[1] == 0x50
                    && bytes[2] == 0x4E
                    && bytes[3] == 0x47
                    && bytes[4] == 0x0D
                    && bytes[5] == 0x0A
                    && bytes[6] == 0x1A
                    && bytes[7] == 0x0A;
        }

        private static int[] visibleBounds(NativeImage image) {
            int width = image.getWidth();
            int height = image.getHeight();
            int minX = width;
            int minY = height;
            int maxX = -1;
            int maxY = -1;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int argb = image.getPixel(x, y);
                    int alpha = (argb >>> 24) & 0xFF;
                    if (alpha <= 8) continue;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }

            if (maxX < minX || maxY < minY) {
                return new int[]{0, 0, width, height};
            }
            return new int[]{minX, minY, maxX - minX + 1, maxY - minY + 1};
        }

        private void register(NativeImage image, int decodedWidth, int decodedHeight) {
            Identifier id = textureId(key);
            DynamicTexture texture = null;
            int[] visible = visibleBounds(image);
            try {
                texture = new DynamicTexture(() -> "NSM Wiki image " + source.displayName(), image);
                Minecraft.getInstance().getTextureManager().register(id, texture);
                texture.upload();
                identifier = id;
                width = decodedWidth;
                height = decodedHeight;
                contentX = visible[0];
                contentY = visible[1];
                contentWidth = visible[2];
                contentHeight = visible[3];
                error = "";
                status = Status.READY;
            } catch (RuntimeException | LinkageError exception) {
                if (texture != null) {
                    texture.close();
                } else {
                    image.close();
                }
                fail("Texture registration failed: " + exception.getMessage());
            }
        }

        private synchronized void fail(String message) {
            error = message == null || message.isBlank() ? "Unknown image error" : message;
            failedAt = System.currentTimeMillis();
            status = Status.FAILED;
            System.err.println("[NSM Wiki Image] " + error + " | " + source.url());
        }

        private Snapshot snapshot() {
            return new Snapshot(status, identifier, width,
                    contentX, contentY, contentWidth, contentHeight,
                    height, error, credits
            );
        }

        private void releaseTexture() {
            Identifier id = identifier;
            if (id != null) {
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().getTextureManager().release(id));
            }
        }
    }
}
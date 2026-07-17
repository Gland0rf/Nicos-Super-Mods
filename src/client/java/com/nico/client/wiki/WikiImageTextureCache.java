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

    public record Snapshot(Status status, Identifier textureId, int width, int height, String error) {
        public Snapshot {
            status = status == null ? Status.EMPTY : status;
            width = Math.max(0, width);
            height = Math.max(0, height);
            error = error == null ? "" : error;
        }

        public static Snapshot empty() {
            return new Snapshot(Status.EMPTY, null, 0, 0, "");
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
        private volatile String error = "";
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
                request = HttpRequest.newBuilder(URI.create(resolved.url()))
                        .timeout(Duration.ofSeconds(20))
                        .header("User-Agent", "NSM-Mod/1.0 Hypixel-SkyBlock-Wiki-Reader")
                        .header("Referer", "https://hypixelskyblock.minecraft.wiki/")
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

        private void register(NativeImage image, int decodedWidth, int decodedHeight) {
            Identifier id = textureId(key);
            DynamicTexture texture = null;
            try {
                texture = new DynamicTexture(() -> "NSM Wiki image " + source.displayName(), image);
                Minecraft.getInstance().getTextureManager().register(id, texture);
                texture.upload();
                identifier = id;
                width = decodedWidth;
                height = decodedHeight;
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
            System.err.println("[Wiki image] " + error + " | " + source.url());
        }

        private Snapshot snapshot() {
            return new Snapshot(status, identifier, width, height, error);
        }

        private void releaseTexture() {
            Identifier id = identifier;
            if (id != null) {
                Minecraft.getInstance().execute(() -> Minecraft.getInstance().getTextureManager().release(id));
            }
        }
    }
}
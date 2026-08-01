package com.nico.client.wiki;

import com.google.gson.*;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public final class WikiBrowserStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final WikiBrowserStore INSTANCE = new WikiBrowserStore();

    private final Map<String, Bookmark> bookmarks = new LinkedHashMap<>();
    private boolean loaded;
    private boolean websiteStyle = true;

    private WikiBrowserStore() {
    }

    public static WikiBrowserStore get() {
        INSTANCE.ensureLoaded();
        return INSTANCE;
    }

    public synchronized boolean websiteStyle() {
        ensureLoaded();
        return websiteStyle;
    }

    public synchronized void setWebsiteStyle(boolean enabled) {
        ensureLoaded();
        websiteStyle = enabled;
        saveQuietly();
    }

    public synchronized boolean isBookmarked(URI uri) {
        ensureLoaded();
        return uri != null && bookmarks.containsKey(key(uri));
    }

    public synchronized boolean toggleBookmark(String title, URI uri) {
        ensureLoaded();
        if (uri == null) {
            return false;
        }

        String key = key(uri);
        if (bookmarks.remove(key) != null) {
            saveQuietly();
            return false;
        }

        bookmarks.put(key, new Bookmark(
                title == null || title.isBlank() ? articleLabel(uri) : title.trim(),
                stripFragment(uri).toString()
        ));
        saveQuietly();
        return true;
    }

    public synchronized List<Bookmark> bookmarks() {
        ensureLoaded();
        return List.copyOf(bookmarks.values());
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;

        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                return;
            }

            JsonObject root = parsed.getAsJsonObject();
            if (root.has("websiteStyle") && root.get("websiteStyle").isJsonPrimitive()) {
                websiteStyle = root.get("websiteStyle").getAsBoolean();
            }

            JsonArray array = root.has("bookmarks") && root.get("bookmarks").isJsonArray()
                    ? root.getAsJsonArray("bookmarks")
                    : new JsonArray();

            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String title = string(object, "title");
                String uriText = string(object, "uri");
                if (uriText.isBlank()) {
                    continue;
                }
                try {
                    URI uri = URI.create(uriText);
                    bookmarks.put(key(uri), new Bookmark(
                            title.isBlank() ? articleLabel(uri) : title,
                            stripFragment(uri).toString()
                    ));
                } catch (IllegalArgumentException ignored) {
                    // Ignore a broken user entry rather than failing the browser.
                }
            }
        } catch (IOException | RuntimeException exception) {
            System.err.println("[Wiki browser] Could not read settings: " + exception.getMessage());
        }
    }

    private synchronized void saveQuietly() {
        Path path = configPath();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");

        JsonObject root = new JsonObject();
        root.addProperty("websiteStyle", websiteStyle);

        JsonArray array = new JsonArray();
        for (Bookmark bookmark : bookmarks.values()) {
            JsonObject object = new JsonObject();
            object.addProperty("title", bookmark.title());
            object.addProperty("uri", bookmark.uri());
            array.add(object);
        }
        root.add("bookmarks", array);

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            System.err.println("[Wiki browser] Could not save settings: " + exception.getMessage());
        }
    }

    private static Path configPath() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.gameDirectory.toPath()
                .resolve("config")
                .resolve("nsm-wiki-browser.json");
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

    private static String key(URI uri) {
        URI stripped = stripFragment(uri);
        String host = Objects.requireNonNullElse(stripped.getHost(), "").toLowerCase(Locale.ROOT);
        String path = Objects.requireNonNullElse(stripped.getPath(), "");
        return host + path;
    }

    private static URI stripFragment(URI uri) {
        if (uri == null || uri.getFragment() == null) {
            return uri;
        }
        try {
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), uri.getQuery(), null);
        } catch (Exception ignored) {
            return URI.create(uri.toString().split("#", 2)[0]);
        }
    }

    private static String articleLabel(URI uri) {
        String path = uri == null || uri.getPath() == null ? "Wiki" : uri.getPath();
        int slash = path.lastIndexOf('/');
        String value = slash >= 0 ? path.substring(slash + 1) : path;
        try {
            value = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // Keep the original label.
        }
        value = value.replace('_', ' ').trim();
        return value.isBlank() ? "Wiki" : value;
    }

    public record Bookmark(String title, String uri) {
        public Bookmark {
            title = Objects.requireNonNullElse(title, "Wiki").trim();
            uri = Objects.requireNonNullElse(uri, "").trim();
        }

        public URI parsedUri() {
            return URI.create(uri);
        }
    }
}

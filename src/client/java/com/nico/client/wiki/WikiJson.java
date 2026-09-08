package com.nico.client.wiki;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Null-safe accessors for JSON returned by Wiki/Hypixel endpoints and browser state. */
public final class WikiJson {
    private WikiJson() { }

    public static JsonObject object(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key)
                : null;
    }

    public static JsonArray array(JsonObject parent, String key) {
        return parent != null && parent.has(key) && parent.get(key).isJsonArray()
                ? parent.getAsJsonArray(key)
                : null;
    }

    public static String string(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString()
                    : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static int integer(JsonObject object, String key) {
        return integer(object, key, 0);
    }

    public static int integer(JsonObject object, String key, int fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsInt()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static long longValue(JsonObject object, String key, long fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsLong()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    public static double decimal(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsDouble()
                    : 0.0D;
        } catch (RuntimeException ignored) {
            return 0.0D;
        }
    }

    public static boolean booleanValue(JsonObject object, String key, boolean fallback) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsBoolean()
                    : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}

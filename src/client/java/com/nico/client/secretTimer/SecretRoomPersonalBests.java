package com.nico.client.secretTimer;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nico.client.utils.AtomicFiles;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Persists personal-best times for the secret room timer. */
final class SecretRoomPersonalBests {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type ENTRY_MAP_TYPE = new TypeToken<Map<String, Entry>>() { }.getType();
    private static final Path FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("nicos_super_mods")
            .resolve("secret-room-pbs.json");

    private static final Map<String, Entry> entries = new HashMap<>();

    private SecretRoomPersonalBests() { }

    static void load() {
       try {
            if (!Files.exists(FILE)) return;

            String json = Files.readString(FILE, StandardCharsets.UTF_8);
            Map<String, Entry> loaded = GSON.fromJson(json, ENTRY_MAP_TYPE);

            entries.clear();
            if (loaded != null) {
                entries.putAll(loaded);
            }

            System.out.println("[NSM] Loaded " + entries.size() + " secret room PBs.");
       } catch (Throwable throwable) {
            System.out.println("[NSM] Failed to load secret room PBs.");
            throwable.printStackTrace();
       }
    }

    static Entry get(String roomName) {
        return entries.get(roomName);
    }

    static void save(String roomName, long bestMs, int totalSecrets) {
        entries.put(roomName, new Entry(bestMs, System.currentTimeMillis(), totalSecrets));
        persist();
    }

    static boolean isEmpty() {
        return entries.isEmpty();
    }

    static List<Map.Entry<String, Entry>> sortedEntries() {
        List<Map.Entry<String, Entry>> sorted = new ArrayList<>(entries.entrySet());
        sorted.sort(Comparator.comparing(entry -> entry.getKey().toLowerCase(Locale.ROOT)));
        return sorted;
    }

    static Entry remove(String roomName) {
        Entry removed = entries.remove(roomName);
        if (removed != null) {
            persist();
        }
        return removed;
    }

    static int clear() {
        int count = entries.size();
        if (count == 0) return 0;

        entries.clear();
        persist();
        return count;
    }

    private static void persist() {
        try {
            String json = GSON.toJson(entries, ENTRY_MAP_TYPE);
            AtomicFiles.writeStringAtomically(FILE, json, StandardCharsets.UTF_8);
        } catch (Throwable throwable) {
            System.out.println("[NSM] Failed to save secret room PBs.");
            throwable.printStackTrace();
        }
    }

    static final class Entry {
        private long bestMs;
        private long achievedAtMs;
        private int totalSecrets;

        private Entry() { }

        private Entry(long bestMs, long achievedAtMs, int totalSecrets) {
            this.bestMs = bestMs;
            this.achievedAtMs = achievedAtMs;
            this.totalSecrets = totalSecrets;
        }

        long bestMs() {
            return bestMs;
        }

        int totalSecrets() {
            return totalSecrets;
        }
    }
}
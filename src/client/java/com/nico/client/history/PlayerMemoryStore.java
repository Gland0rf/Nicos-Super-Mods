package com.nico.client.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.nico.client.utils.AtomicFiles;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Local-only memory of players the user has actually encountered. */
public final class PlayerMemoryStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("nicos_super_mods")
            .resolve("player_memory.json");

    private final Map<String, PlayerMemory> players;
    private boolean dirty;

    public PlayerMemoryStore() {
        this.players = load();
    }

    public synchronized PreviousEncounter recordPartyJoin(String playerName, long now) {
        String key = key(playerName);
        if (key.isBlank()) return PreviousEncounter.NONE;

        PlayerMemory memory = players.computeIfAbsent(key, ignored -> PlayerMemory.create(playerName, now));
        PreviousEncounter previous = new PreviousEncounter(
                memory.encounters,
                memory.dungeonRuns,
                memory.lastSeenMillis,
                memory.usualClass()
        );
        memory.displayName = playerName;
        memory.encounters++;
        memory.lastSeenMillis = now;
        if (memory.firstSeenMillis <= 0) memory.firstSeenMillis = now;
        dirty = true;
        return previous;
    }

    public synchronized void observePlayer(String playerName, long now) {
        String key = key(playerName);
        if (key.isBlank()) return;
        PlayerMemory memory = players.computeIfAbsent(key, ignored -> PlayerMemory.create(playerName, now));
        memory.displayName = playerName;
        memory.lastSeenMillis = Math.max(memory.lastSeenMillis, now);
        dirty = true;
    }

    public synchronized void recordDungeonClassSample(String playerName, String clazz) {
        if (clazz == null || clazz.isBlank() || clazz.equalsIgnoreCase("Unknown") || clazz.equalsIgnoreCase("Dead")) return;
        String key = key(playerName);
        if (key.isBlank()) return;
        PlayerMemory memory = players.computeIfAbsent(key, ignored -> PlayerMemory.create(playerName, System.currentTimeMillis()));
        memory.classSamples.merge(clazz, 1L, Long::sum);
        dirty = true;
    }

    public synchronized void recordDungeonRun(Collection<String> teammates, long now) {
        for (String teammate : teammates) {
            String key = key(teammate);
            if (key.isBlank()) continue;
            PlayerMemory memory = players.computeIfAbsent(key, ignored -> PlayerMemory.create(teammate, now));
            memory.displayName = teammate;
            memory.dungeonRuns++;
            memory.lastSeenMillis = now;
            dirty = true;
        }
    }

    public synchronized PlayerMemory get(String playerName) {
        return players.get(key(playerName));
    }

    public synchronized void saveIfDirty() {
        if (!dirty) return;
        save();
    }

    public synchronized void save() {
        try {
            AtomicFiles.writeAtomically(FILE, temporary -> {
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    GSON.toJson(players, writer);
                }
            });
            dirty = false;
        } catch (IOException exception) {
            System.err.println("[NSM Party Memory] Could not save player memory: " + exception.getMessage());
        }
    }

    private static Map<String, PlayerMemory> load() {
        if (!Files.exists(FILE)) return new HashMap<>();
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Type type = TypeToken.getParameterized(Map.class, String.class, PlayerMemory.class).getType();
            Map<String, PlayerMemory> loaded = GSON.fromJson(reader, type);
            if (loaded == null) return new HashMap<>();
            loaded.values().forEach(PlayerMemory::normalize);
            return new HashMap<>(loaded);
        } catch (IOException | RuntimeException exception) {
            System.err.println("[NSM Party Memory] Could not load player memory: " + exception.getMessage());
            return new HashMap<>();
        }
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public static final class PlayerMemory {
        public String displayName = "";
        public long firstSeenMillis;
        public long lastSeenMillis;
        public int encounters;
        public int dungeonRuns;
        public Map<String, Long> classSamples = new HashMap<>();

        static PlayerMemory create(String displayName, long now) {
            PlayerMemory memory = new PlayerMemory();
            memory.displayName = displayName;
            memory.firstSeenMillis = now;
            memory.lastSeenMillis = now;
            return memory;
        }

        void normalize() {
            if (displayName == null) displayName = "";
            if (classSamples == null) classSamples = new HashMap<>();
        }

        public String usualClass() {
            return classSamples.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("Unknown");
        }
    }

    public record PreviousEncounter(int encounters, int dungeonRuns, long lastSeenMillis, String usualClass) {
        static final PreviousEncounter NONE = new PreviousEncounter(0, 0, 0L, "Unknown");

        public boolean known() {
            return encounters > 0 || dungeonRuns > 0;
        }

        public long daysSince(long now) {
            if (lastSeenMillis <= 0 || now <= lastSeenMillis) return 0;
            return Duration.between(Instant.ofEpochMilli(lastSeenMillis), Instant.ofEpochMilli(now)).toDays();
        }
    }
}

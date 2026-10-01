package com.nico.client.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nico.client.utils.AtomicFiles;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

public final class WrappedStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("nicos_super_mods")
            .resolve("wrapped.json");

    private Data data;
    private boolean dirty;

    public WrappedStore() {
        this.data = load();
    }

    public synchronized DayStats today() {
        return day(LocalDate.now());
    }

    public synchronized DayStats day(LocalDate date) {
        String key = date.toString();
        DayStats stats = data.days.computeIfAbsent(key, ignored -> new DayStats());
        dirty = true;
        return stats;
    }

    public synchronized void markDirty() {
        dirty = true;
    }

    public synchronized boolean recordEventInstance(String event, String instanceId) {
        if (event == null || event.isBlank() || instanceId == null || instanceId.isBlank()) return false;

        for (DayStats existing : data.days.values()) {
            existing.normalize();
            Set<String> ids = existing.timedEventInstances.get(event);
            if (ids != null && ids.contains(instanceId)) return false;
        }

        DayStats today = day(LocalDate.now());
        today.timedEventInstances.computeIfAbsent(event, ignored -> new HashSet<>()).add(instanceId);
        dirty = true;
        return true;
    }

    public synchronized WrappedSnapshot snapshot(int days) {
        int safeDays = Math.max(1, Math.min(3650, days));
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(safeDays - 1L);
        WrappedSnapshot result = new WrappedSnapshot(start, end);

        for (Map.Entry<String, DayStats> entry : data.days.entrySet()) {
            LocalDate date;
            try {
                date = LocalDate.parse(entry.getKey());
            } catch (RuntimeException ignored) {
                continue;
            }
            if (date.isBefore(start) || date.isAfter(end)) continue;
            result.merge(date, entry.getValue());
        }
        return result;
    }

    public synchronized void saveIfDirty() {
        if (!dirty) return;
        save();
    }

    public synchronized void save() {
        try {
            AtomicFiles.writeAtomically(FILE, temporary -> {
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    GSON.toJson(data, writer);
                }
            });
            dirty = false;
        } catch (IOException exception) {
            System.err.println("[NSM Wrapped] Could not save wrapped data: " + exception.getMessage());
        }
    }

    private static Data load() {
        if (!Files.exists(FILE)) return new Data();

        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Data loaded = GSON.fromJson(reader, Data.class);
            if (loaded == null) return new Data();
            loaded.normalize();
            return loaded;
        } catch (IOException | RuntimeException exception) {
            System.err.println("[NSM Wrapped] Could not load wrapped data: " + exception.getMessage());
            return new Data();
        }
    }

    private static final class Data {
        Map<String, DayStats> days = new TreeMap<>();

        void normalize() {
            if (days == null) days = new TreeMap<>();
            days.values().forEach(DayStats::normalize);
        }
    }

    public static final class DayStats {
        public long playtimeSeconds;
        public int sessions;
        public long longestSessionSeconds;

        public long coinsEarned;
        public long coinsSpent;
        public long taxesPaid;
        public long biggestPurchase;
        public String biggestPurchaseItem = "";
        public long biggestSale;
        public String biggestSaleItem = "";

        public Map<String, Long> itemSeconds = new HashMap<>();
        public Map<String, Long> armorSetSeconds = new HashMap<>();
        public Map<String, Long> petSeconds = new HashMap<>();
        public Map<String, Long> areaSeconds = new HashMap<>();

        public int dungeonRuns;
        public int dungeonFailedRuns;
        public int dungeonDeaths;
        public long fastestDungeonSeconds;
        public long slowestDungeonSeconds;
        public Map<String, Integer> dungeonFloorRuns = new HashMap<>();
        public Map<String, Integer> dungeonTeammateRuns = new HashMap<>();

        public int slayerBosses;
        public int slayerDeaths;
        public int slayerRngDrops;
        public Map<String, Integer> slayerBossesByType = new HashMap<>();
        public Map<String, Long> fastestSlayerMillis = new HashMap<>();

        public Map<String, Long> minedResources = new HashMap<>();
        public int commissionsCompleted;
        public long forgeBusySeconds;

        public long cropsBroken;
        public Map<String, Long> farmedCrops = new HashMap<>();
        public int jacobsContests;
        public int pestsKilled;

        public long waterFishingSeconds;
        public long lavaFishingSeconds;
        public int seaCreaturesKilled;
        public int rareSeaCreatures;
        public int fishingDeaths;

        public long logsBroken;
        public Map<String, Long> choppedWoods = new HashMap<>();

        public int kuudraRuns;
        public int kuudraFailedRuns;
        public long fastestKuudraSeconds;
        public long slowestKuudraSeconds;
        public Map<String, Integer> kuudraTierRuns = new HashMap<>();

        public Map<String, Set<String>> timedEventInstances = new HashMap<>();

        public long riftMotesEarned;
        public long riftMotesSpent;
        public Map<String, Integer> riftDeathsByCause = new HashMap<>();

        public int chatMessagesSent;
        public Map<String, Integer> encounteredPlayers = new HashMap<>();
        public Map<String, Integer> reunions = new HashMap<>();
        public Map<String, Integer> reunionGapDays = new HashMap<>();

        void normalize() {
            if (biggestPurchaseItem == null) biggestPurchaseItem = "";
            if (biggestSaleItem == null) biggestSaleItem = "";
            if (itemSeconds == null) itemSeconds = new HashMap<>();
            if (armorSetSeconds == null) armorSetSeconds = new HashMap<>();
            if (petSeconds == null) petSeconds = new HashMap<>();
            if (areaSeconds == null) areaSeconds = new HashMap<>();
            if (dungeonFloorRuns == null) dungeonFloorRuns = new HashMap<>();
            if (dungeonTeammateRuns == null) dungeonTeammateRuns = new HashMap<>();
            if (slayerBossesByType == null) slayerBossesByType = new HashMap<>();
            if (fastestSlayerMillis == null) fastestSlayerMillis = new HashMap<>();
            if (minedResources == null) minedResources = new HashMap<>();
            if (farmedCrops == null) farmedCrops = new HashMap<>();
            if (choppedWoods == null) choppedWoods = new HashMap<>();
            if (kuudraTierRuns == null) kuudraTierRuns = new HashMap<>();
            if (timedEventInstances == null) timedEventInstances = new HashMap<>();
            if (riftDeathsByCause == null) riftDeathsByCause = new HashMap<>();
            if (encounteredPlayers == null) encounteredPlayers = new HashMap<>();
            if (reunions == null) reunions = new HashMap<>();
            if (reunionGapDays == null) reunionGapDays = new HashMap<>();
        }
    }

    public static final class WrappedSnapshot {
        public final LocalDate start;
        public final LocalDate end;
        public final Map<LocalDate, Long> playtimeByDay = new TreeMap<>();

        public long playtimeSeconds;
        public int sessions;
        public long longestSessionSeconds;
        public long coinsEarned;
        public long coinsSpent;
        public long taxesPaid;
        public long biggestPurchase;
        public String biggestPurchaseItem = "";
        public long biggestSale;
        public String biggestSaleItem = "";

        public final Map<String, Long> itemSeconds = new HashMap<>();
        public final Map<String, Long> armorSetSeconds = new HashMap<>();
        public final Map<String, Long> petSeconds = new HashMap<>();
        public final Map<String, Long> areaSeconds = new HashMap<>();

        public int dungeonRuns;
        public int dungeonFailedRuns;
        public int dungeonDeaths;
        public long fastestDungeonSeconds;
        public long slowestDungeonSeconds;
        public final Map<String, Integer> dungeonFloorRuns = new HashMap<>();
        public final Map<String, Integer> dungeonTeammateRuns = new HashMap<>();

        public int slayerBosses;
        public int slayerDeaths;
        public int slayerRngDrops;
        public final Map<String, Integer> slayerBossesByType = new HashMap<>();
        public final Map<String, Long> fastestSlayerMillis = new HashMap<>();

        public final Map<String, Long> minedResources = new HashMap<>();
        public int commissionsCompleted;
        public long forgeBusySeconds;

        public long cropsBroken;
        public final Map<String, Long> farmedCrops = new HashMap<>();
        public int jacobContests;
        public int pestsKilled;

        public long waterFishingSeconds;
        public long lavaFishingSeconds;
        public int seaCreaturesKilled;
        public int rareSeaCreatures;
        public int fishingDeaths;

        public long logsBroken;
        public final Map<String, Long> choppedWoods = new HashMap<>();

        public int kuudraRuns;
        public int kuudraFailedRuns;
        public long fastestKuudraSeconds;
        public long slowestKuudraSeconds;
        public final Map<String, Integer> kuudraTierRuns = new HashMap<>();

        public final Map<String, Set<String>> timedEventInstances = new HashMap<>();
        public long riftMotesEarned;
        public long riftMotesSpent;
        public final Map<String, Integer> riftDeathsByCause = new HashMap<>();
        public int chatMessagesSent;
        public final Map<String, Integer> encounteredPlayers = new HashMap<>();
        /** Number of distinct days in this Wrapped range on which each player was encountered. */
        public final Map<String, Integer> encounterDays = new HashMap<>();
        public final Map<String, Integer> reunions = new HashMap<>();
        /** Largest observed gap before meeting a player again, in real days. */
        public final Map<String, Integer> reunionGapDays = new HashMap<>();

        WrappedSnapshot(LocalDate start, LocalDate end) {
            this.start = start;
            this.end = end;
        }

        void merge(LocalDate date, DayStats day) {
            day.normalize();
            playtimeByDay.put(date, day.playtimeSeconds);
            playtimeSeconds += day.playtimeSeconds;
            sessions += day.sessions;
            longestSessionSeconds = Math.max(longestSessionSeconds, day.longestSessionSeconds);
            coinsEarned += day.coinsEarned;
            coinsSpent += day.coinsSpent;
            taxesPaid += day.taxesPaid;
            if (day.biggestPurchase > biggestPurchase) {
                biggestPurchase = day.biggestPurchase;
                biggestPurchaseItem = day.biggestPurchaseItem;
            }
            if (day.biggestSale > biggestSale) {
                biggestSale = day.biggestSale;
                biggestSaleItem = day.biggestSaleItem;
            }

            mergeLong(itemSeconds, day.itemSeconds);
            mergeLong(armorSetSeconds, day.armorSetSeconds);
            mergeLong(petSeconds, day.petSeconds);
            mergeLong(areaSeconds, day.areaSeconds);

            dungeonRuns += day.dungeonRuns;
            dungeonFailedRuns += day.dungeonFailedRuns;
            dungeonDeaths += day.dungeonDeaths;
            fastestDungeonSeconds = minPositive(fastestDungeonSeconds, day.fastestDungeonSeconds);
            slowestDungeonSeconds = Math.max(slowestDungeonSeconds, day.slowestDungeonSeconds);
            mergeInt(dungeonFloorRuns, day.dungeonFloorRuns);
            mergeInt(dungeonTeammateRuns, day.dungeonTeammateRuns);

            slayerBosses += day.slayerBosses;
            slayerDeaths += day.slayerDeaths;
            slayerRngDrops += day.slayerRngDrops;
            mergeInt(slayerBossesByType, day.slayerBossesByType);
            mergeFastest(fastestSlayerMillis, day.fastestSlayerMillis);

            mergeLong(minedResources, day.minedResources);
            commissionsCompleted += day.commissionsCompleted;
            forgeBusySeconds += day.forgeBusySeconds;

            cropsBroken += day.cropsBroken;
            mergeLong(farmedCrops, day.farmedCrops);
            jacobContests += day.jacobsContests;
            pestsKilled += day.pestsKilled;

            waterFishingSeconds +=  day.waterFishingSeconds;
            lavaFishingSeconds +=  day.lavaFishingSeconds;
            seaCreaturesKilled += day.seaCreaturesKilled;
            rareSeaCreatures += day.rareSeaCreatures;
            fishingDeaths += day.fishingDeaths;

            logsBroken += day.logsBroken;
            mergeLong(choppedWoods, day.choppedWoods);

            kuudraRuns += day.kuudraRuns;
            kuudraFailedRuns += day.kuudraFailedRuns;
            fastestKuudraSeconds = minPositive(fastestKuudraSeconds, day.fastestKuudraSeconds);
            slowestKuudraSeconds = Math.max(slowestKuudraSeconds, day.slowestKuudraSeconds);
            mergeInt(kuudraTierRuns, day.kuudraTierRuns);

            day.timedEventInstances.forEach((event, instances) -> {
                timedEventInstances.computeIfAbsent(event, ignored -> new HashSet<>()).addAll(instances);
            });

            riftMotesEarned += day.riftMotesEarned;
            riftMotesSpent += day.riftMotesSpent;
            mergeInt(riftDeathsByCause, day.riftDeathsByCause);

            chatMessagesSent +=  day.chatMessagesSent;
            mergeInt(encounteredPlayers, day.encounteredPlayers);
            day.encounteredPlayers.keySet().forEach(player -> encounterDays.merge(player, 1, Integer::sum));
            mergeInt(reunions, day.reunions);
            mergeMaxInt(reunionGapDays, day.reunionGapDays);
        }

        public long averageSessionSeconds() {
            return sessions <= 0 ? 0 : playtimeSeconds / sessions;
        }

        public long netCoins() {
            return coinsEarned - coinsSpent;
        }

        public String topLong(Map<String, Long> values) {
            return values.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("");
        }

        public String topInt(Map<String, Integer> values) {
            return values.entrySet().stream()
                    .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("");
        }

        private static long minPositive(long left, long right) {
            if (left <= 0) return right;
            if (right <= 0) return left;
            return Math.min(left, right);
        }

        private static void mergeLong(Map<String, Long> into, Map<String, Long> from) {
            from.forEach((key, value) -> into.merge(key, value, Long::sum));
        }

        private static void mergeInt(Map<String, Integer> into, Map<String, Integer> from) {
            from.forEach((key, value) -> into.merge(key, value, Integer::sum));
        }

        private static void mergeMaxInt(Map<String, Integer> into, Map<String, Integer> from) {
            from.forEach((key, value) -> into.merge(key, value, Math::max));
        }

        private static void mergeFastest(Map<String, Long> into, Map<String, Long> from) {
            from.forEach((key, value) -> into.merge(key, value, WrappedSnapshot::minPositive));
        }
   }
}

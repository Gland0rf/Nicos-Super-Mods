package com.nico.client.secretTimer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.nico.client.configuration.NsmConfig;
import com.nico.client.configuration.category.CategoryDungeons;
import com.nico.client.dungeon.DungeonScanner;
import com.nico.client.dungeon.DungeonState;
import com.nico.client.dungeon.DungeonTeammateScanner;
import com.nico.client.utils.AtomicFiles;
import com.nico.client.utils.LocationUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SecretRoomTimerClient {
    private SecretRoomTimerClient() {}

    private static final long SELF_SECRET_CONFIRM_WINDOW_MS = 5000L;
    private static final long DUPLICATE_BLOCK_INTERACTION_WINDOW_MS = 250L;
    private static final double CHEST_DROP_IGNORE_RADIUS_SQ = 2.0D * 2.0D;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type PB_MAP_TYPE = new TypeToken<Map<String, PbEntry>>() {}.getType();

    private static final Path PB_FILE =
            FabricLoader.getInstance()
                    .getConfigDir()
                    .resolve("nicos_super_mods")
                    .resolve("secret-room-pbs.json");

    private static final Map<String, PbEntry> personalBests = new HashMap<>();

    private static final Map<String, Attempt> attempts = new HashMap<>();
    private static final Map<String, Integer> knownFoundByRoom = new HashMap<>();
    private static final Map<String, Integer> knownTotalByRoom = new HashMap<>();

    private static final Map<String, Deque<Long>> pendingSelfPickups = new HashMap<>();
    private static final Map<String, Deque<Long>> pendingCounterIncrements = new HashMap<>();

    private static final Map<String, Deque<Long>> pendingIgnoredSelfPickups = new HashMap<>();
    private static final Map<String, Deque<PendingPotentialSecret>> pendingPotentialSelfSecrets = new HashMap<>();
    private static final Map<String, Long> lastConfirmedBlockSecretAtByRoom = new HashMap<>();
    private static final Map<String, List<BlockPos>> chestSecretPositionsByRoom = new HashMap<>();
    private static final Map<String, Deque<PendingChestSecret>> pendingChestSecretsByRoom = new HashMap<>();

    private static final Map<String, Set<Long>> seenSelfSecretPositions = new HashMap<>();

    private static boolean lastInDungeonRoom = false;
    private static int tickCounter = 0;
    private static boolean initialized = false;

    public static synchronized void init() {
        if (initialized) return;

        loadPbs();
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        initialized = true;
    }

    public static void onRoomSecretsPacket(int foundSecrets, int totalSecrets) {
        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            mc.execute(() -> onRoomSecretsPacket(foundSecrets, totalSecrets));
            return;
        }

        if (!isDungeonRoomContext(mc, false)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        // Room stacking uses these counters even when the timer HUD/feature itself is disabled.
        // Because the room stacking feature also relies on those. Maybe a better organisation would be smark idk too lazy
        if (!enabled()) {
            knownFoundByRoom.put(roomName, Math.max(0, foundSecrets));
            if (totalSecrets > 0) {
                knownTotalByRoom.put(roomName, totalSecrets);
            }
            return;
        }

        onRoomSecretCounterUpdate(roomName, foundSecrets, totalSecrets);
    }

    public static void onSecretPickup(BlockPos secretPos) {
        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            BlockPos immutablePos = secretPos == null ? null : secretPos.immutable();
            mc.execute(() -> onSecretPickup(immutablePos));
            return;
        }

        if (!enabled()) return;

        if (!isDungeonRoomContext(mc, true)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        if (shouldIgnoreSecretPickup(mc, secretPos)) {
            long now = System.currentTimeMillis();
            Deque<Long> ignoredPickups = pendingIgnoredSelfPickups
                    .computeIfAbsent(roomName, ignored -> new ArrayDeque<>());

            if (ignoredPickups.isEmpty()
                    || now - ignoredPickups.peekLast() > DUPLICATE_BLOCK_INTERACTION_WINDOW_MS) {
                ignoredPickups.addLast(now);
            }

            System.out.println("[NSM] Ignored lever secret pickup at " + secretPos + " in room " + roomName);
            return;
        }

        long now = System.currentTimeMillis();

        if (secretPos != null) {
            Set<Long> seenPositions = seenSelfSecretPositions.computeIfAbsent(
                    roomName,
                    ignored -> new HashSet<>()
            );

            if (!seenPositions.add(secretPos.asLong())) {
                return;
            }
        }

        boolean matchedEarlierCounterIncrement = consumePendingCounterIncrement(roomName, now);

        if (!matchedEarlierCounterIncrement) {
            pendingSelfPickups
                    .computeIfAbsent(roomName, ignored -> new ArrayDeque<>())
                    .addLast(now);
        }

        countSelfSecret(roomName, now, matchedEarlierCounterIncrement, secretPos);
    }

    public static void onRoomSecretCounterUpdate(String roomName, int foundSecrets, int totalSecrets) {
        if (roomName == null || roomName.isBlank() || roomName.equals("Unknown")) return;

        long now = System.currentTimeMillis();

        if (totalSecrets > 0) {
            knownTotalByRoom.put(roomName, totalSecrets);

            Attempt attempt = attempts.get(roomName);
            if (attempt != null) {
                attempt.totalSecrets = totalSecrets;
            }
        }

        Integer previousFound = knownFoundByRoom.get(roomName);

        if (previousFound == null) {
            knownFoundByRoom.put(roomName, foundSecrets);

            for (int i = 0; i < foundSecrets; i++) {
                PendingChestSecret chestSecret = consumePendingChestSecret(roomName, now);
                if (chestSecret != null) {
                    Set<Long> seenPositions = seenSelfSecretPositions.computeIfAbsent(roomName, ignored -> new HashSet<>());
                    if (seenPositions.add(chestSecret.pos.asLong())) {
                        rememberChestSecretPosition(roomName, chestSecret.pos);
                        countSelfSecret(roomName, now, true, chestSecret.pos);
                    }
                    continue;
                }

                PendingPotentialSecret potentialSecret = consumePendingPotentialSecret(roomName, now);
                if (potentialSecret != null) {
                    rememberConfirmedPotentialSecret(roomName, potentialSecret, now);
                    if (potentialSecret.pos == null) {
                        countSelfSecret(roomName, now, true, null);
                    } else {
                        Set<Long> seenPositions = seenSelfSecretPositions.computeIfAbsent(roomName, ignored -> new HashSet<>());
                        if (seenPositions.add(potentialSecret.pos.asLong())) {
                            countSelfSecret(roomName, now, true, potentialSecret.pos);
                        }
                    }
                    continue;
                }

                if (consumePendingSelfPickup(roomName, now)) {
                    continue;
                }

                if (consumePendingIgnoredSelfPickup(roomName, now)) {
                    continue;
                }

                addPendingCounterIncrement(roomName, now);
            }

            expireUnmatchedCounterIncrements(now);

            Attempt attempt = attempts.get(roomName);
            if (attempt != null) {
                tryFinish(roomName, attempt);
            }

            return;
        }

        if (foundSecrets < previousFound) {
            resetRoomState(roomName);

            knownFoundByRoom.put(roomName, foundSecrets);

            if (totalSecrets > 0) {
                knownTotalByRoom.put(roomName, totalSecrets);
            }

            return;
        }

        int delta = foundSecrets - previousFound;
        knownFoundByRoom.put(roomName, foundSecrets);

        for (int i = 0; i < delta; i++) {
            PendingChestSecret chestSecret = consumePendingChestSecret(roomName, now);
            if (chestSecret != null) {
                Set<Long> seenPositions = seenSelfSecretPositions.computeIfAbsent(roomName, ignored -> new HashSet<>());
                if (seenPositions.add(chestSecret.pos.asLong())) {
                    rememberChestSecretPosition(roomName, chestSecret.pos);
                    countSelfSecret(roomName, now, true, chestSecret.pos);
                    continue;
                }
            }

            PendingPotentialSecret potentialSecret = consumePendingPotentialSecret(roomName, now);
            if (potentialSecret != null) {
                rememberConfirmedPotentialSecret(roomName, potentialSecret, now);
                if (potentialSecret.pos == null) {
                    countSelfSecret(roomName, now, true, null);
                } else {
                    Set<Long> seenPositions = seenSelfSecretPositions.computeIfAbsent(roomName, ignored -> new HashSet<>());
                    if (seenPositions.add(potentialSecret.pos.asLong())) {
                        countSelfSecret(roomName, now, true, potentialSecret.pos);
                    }
                }
                continue;
            }

            if (consumePendingSelfPickup(roomName, now)) {
                continue;
            }

            if (consumePendingIgnoredSelfPickup(roomName, now)) {
                continue;
            }

            addPendingCounterIncrement(roomName, now);
        }

        expireUnmatchedCounterIncrements(now);

        Attempt attempt = attempts.get(roomName);
        if (attempt != null) {
            tryFinish(roomName, attempt);
        }
    }

    public static void onLockedChestMessage() {
        if (!enabled()) return;

        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            mc.execute(SecretRoomTimerClient::onLockedChestMessage);
            return;
        }

        if (!isDungeonRoomContext(mc, false)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        Deque<PendingChestSecret> queue = pendingChestSecretsByRoom.get(roomName);
        if (queue == null || queue.isEmpty()) return;

        PendingChestSecret removed = queue.removeLast();

        if (queue.isEmpty()) {
            pendingChestSecretsByRoom.remove(roomName);
        }

        System.out.println("[NSM] Removed locked chest pending secret at " + removed.pos + " in room " + roomName);
    }

    private static boolean shouldIgnoreSecretPickup(Minecraft mc, BlockPos secretPos) {
        if (mc.level == null || secretPos == null) return false;

        Block block = mc.level.getBlockState(secretPos).getBlock();

        return block == Blocks.LEVER;
    }

    private static void tryFinish(String roomName, Attempt attempt) {
        int knownFound = knownFoundByRoom.getOrDefault(roomName, -1);

        if (attempt.finished) return;
        if (attempt.invalidated) return;
        if (attempt.totalSecrets <= 0) return;
        if (attempt.selfSecrets < attempt.totalSecrets) return;

        //int knownFound = knownFoundByRoom.getOrDefault(roomName, -1);

        if (knownFound >= 0 && knownFound < attempt.totalSecrets) {
            return;
        }

        attempt.finished = true;

        long durationMs = Math.max(0L, attempt.lastSecretAtMs - attempt.startedAtMs);

        PbEntry oldPb = personalBests.get(roomName);
        boolean newPb = oldPb == null || oldPb.bestMs <= 0 || durationMs < oldPb.bestMs;

        if (newPb) {
            PbEntry newEntry = new PbEntry();
            newEntry.bestMs = durationMs;
            newEntry.achievedAtMs = System.currentTimeMillis();
            newEntry.totalSecrets = attempt.totalSecrets;

            personalBests.put(roomName, newEntry);
            savePbs();

            if (config().showCompletionMessage) {
                String previousText = oldPb == null || oldPb.bestMs <= 0
                        ? ""
                        : " §7Previous: §e" + formatDuration(oldPb.bestMs);

                send("§6§l[NSM] NEW PB §b" + roomName +
                        "§6: §e" + formatDuration(durationMs) +
                        " §7(" + attempt.totalSecrets + " secrets)" +
                        previousText);
            }
        } else {
            if (config().showCompletionMessage && !config().onlyAnnounceNewPbs) {
                send("§6[NSM] Completed §b" + roomName +
                        "§6 in §e" + formatDuration(durationMs) +
                        " §7PB: §e" + formatDuration(oldPb.bestMs));
            }
        }

        attempts.remove(roomName);
    }

    private static boolean consumePendingSelfPickup(String roomName, long now) {
        Deque<Long> queue = pendingSelfPickups.get(roomName);
        if (queue == null) return false;

        while (!queue.isEmpty() && now - queue.peekFirst() > SELF_SECRET_CONFIRM_WINDOW_MS) {
            queue.removeFirst();
        }

        boolean matched = !queue.isEmpty();

        if (matched) {
            queue.removeFirst();
        }

        if (queue.isEmpty()) {
            pendingSelfPickups.remove(roomName);
        }

        return matched;
    }

    private static boolean consumePendingCounterIncrement(String roomName, long now) {
        Deque<Long> queue = pendingCounterIncrements.get(roomName);
        if (queue == null) return false;

        while (!queue.isEmpty() && now - queue.peekFirst() > SELF_SECRET_CONFIRM_WINDOW_MS) {
            long observedAtMs = queue.removeFirst();
            resolveUnmatchedCounerIncrement(roomName, observedAtMs);
        }

        boolean matched = !queue.isEmpty();

        if (matched) {
            queue.removeFirst();
        }

        if (queue.isEmpty()) {
            pendingCounterIncrements.remove(roomName);
        }

        return matched;
    }

    private static void addPendingCounterIncrement(String roomName, long now) {
        pendingCounterIncrements
                .computeIfAbsent(roomName, ignored -> new ArrayDeque<>())
                .addLast(now);
    }

    private static void expireUnmatchedCounterIncrements(long now) {
        Iterator<Map.Entry<String, Deque<Long>>> iterator =
                pendingCounterIncrements.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, Deque<Long>> entry = iterator.next();

            String roomName = entry.getKey();
            Deque<Long> queue = entry.getValue();

            while (!queue.isEmpty() && now - queue.peekFirst() > SELF_SECRET_CONFIRM_WINDOW_MS) {
                long observedAtMs = queue.removeFirst();
                resolveUnmatchedCounerIncrement(roomName, observedAtMs);
            }

            if (queue.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private static void resolveUnmatchedCounerIncrement(String roomName, long observedAtMs) {
        // If no other player is physically in this dungeon room, an unmatched room
        // counter increment cannot reasonably be a teammate's secret. Treat it as a
        // missed local detection instead of invalidating an otherwise valid solo run.
        if (!hasOtherPlayerInRoom(roomName)) {
            creditMissedSoloSecret(roomName, observedAtMs);
            return;
        }

        Attempt attempt = attempts.get(roomName);

        if (attempt == null) return;
        if (attempt.finished) return;
        if (attempt.invalidated) return;

        attempt.invalidated = true;

        System.out.println("[NSM] ATTEMPT INVALIDATED for " + roomName
                + " because an unmatched counter increment occurred while another player was in the room");
    }

    private static boolean hasOtherPlayerInRoom(String roomName) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || roomName == null) return true;
        try {
            Set<String> dungeonTeammates = DungeonTeammateScanner.getDungeonTeammateNames();

            for (net.minecraft.world.entity.player.Player player : mc.level.players()) {
                if (player == mc.player) continue;

                String playerName = player.getName().getString();
                if (!containsIgnoreCase(dungeonTeammates, playerName)) continue;

                String otherRoom = DungeonScanner.getRoomNameForPlayer(player);
                if (roomName.equals(otherRoom)) {
                    System.out.println("[NSM] Secret timer saw dungeon teammate "
                            + playerName + " in room " + roomName);
                    return true;
                }
            }

            return false;
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            // Be conservative if room/player detection fails.
            return true;
        }
    }

    private static boolean containsIgnoreCase(Set<String> names, String wantedName) {
        if (names == null || names.isEmpty() || wantedName == null) return false;

        for (String name : names) {
            if (name != null && name.equalsIgnoreCase(wantedName)) return true;
        }

        return false;
    }

    private static void creditMissedSoloSecret(String roomName, long observedAtMs) {
        int knownFound = knownFoundByRoom.getOrDefault(roomName, 0);
        int knownTotal = knownTotalByRoom.getOrDefault(roomName, -1);
        if (knownFound <= 0) return;

        Attempt attempt = attempts.get(roomName);

        if (attempt == null) {
            attempt = new Attempt(roomName, observedAtMs, knownTotal);
            attempts.put(roomName, attempt);

            if (config().showStartMessage) {
                send("§a[NSM] Timer started for §b" + roomName + "§a.");
            }
        }

        if (attempt.invalidated || attempt.finished) return;

           // A local pickup may already have been counted but its pending confirmation can
        // expire before a very delayed counter packet arrives. Never let the fallback
        // push selfSecrets past the authoritative room counter.
        if (attempt.selfSecrets >= knownFound) {
                System.out.println("[NSM] Unmatched solo counter for " + roomName
                        + " was already accounted for by local pickup detection");
                return;
        }

        attempt.selfSecrets++;
        attempt.lastSecretAtMs = Math.max(attempt.lastSecretAtMs, observedAtMs);

        if (knownTotal > 0) {
            attempt.totalSecrets = knownTotal;
        }

        System.out.println("[NSM] Recovered unmatched secret counter as self pickup for " + roomName
                + " because no other player was in the room");

        if (config().showProgressMessages) {
            if (attempt.totalSecrets > 0) {
                send("§7[NSM] §b" + roomName + "§7: §e"
                        + attempt.selfSecrets + "§7/§e" + attempt.totalSecrets + " §7secrets.");
            } else {
                send("§7[NSM] §b" + roomName + "§7: §e"
                        + attempt.selfSecrets + " §7secrets.");
            }
        }

        tryFinish(roomName, attempt);
    }

    private static void tick() {
        tickCounter++;

        Minecraft mc = Minecraft.getInstance();
        boolean inDungeonRoom = isDungeonRoomContext(mc, false);

        if (lastInDungeonRoom && !inDungeonRoom) {
            resetRunState();
        }

        lastInDungeonRoom = inDungeonRoom;

        long now = System.currentTimeMillis();

        if (tickCounter % 20 == 0) {
            expireUnmatchedCounterIncrements(now);
        }
    }

    private static boolean isDungeonRoomContext(Minecraft mc, boolean log) {
        try {
            if (mc.level == null || mc.player == null) return false;

            boolean roomScannerDetectedDungeon = DungeonScanner.isInDungeon(mc.player);

            return (LocationUtils.isInDungeon()
                    || DungeonState.INSTANCE.getInDungeons()
                    || roomScannerDetectedDungeon)
                    && !DungeonState.INSTANCE.getInBoss();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            return false;
        }
    }

    private static String getCurrentRoomName(Minecraft mc) {
        try {
            if (mc.player == null) return null;

            String roomName = DungeonScanner.getRoomNameForPlayer(mc.player);

            if (roomName == null || roomName.isBlank() || roomName.equals("Unknown")) {
                return null;
            }

            return roomName;
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            return null;
        }
    }

    public static void clearTransientState() {
        resetRunState();
        lastInDungeonRoom = false;
        tickCounter = 0;
    }

    private static void resetRunState() {
        attempts.clear();
        knownFoundByRoom.clear();
        knownTotalByRoom.clear();
        pendingSelfPickups.clear();
        pendingCounterIncrements.clear();
        seenSelfSecretPositions.clear();
        pendingIgnoredSelfPickups.clear();
        pendingPotentialSelfSecrets.clear();
        lastConfirmedBlockSecretAtByRoom.clear();
        chestSecretPositionsByRoom.clear();
        pendingChestSecretsByRoom.clear();
    }

    private static void resetRoomState(String roomName) {
        attempts.remove(roomName);
        knownFoundByRoom.remove(roomName);
        knownTotalByRoom.remove(roomName);
        pendingSelfPickups.remove(roomName);
        pendingCounterIncrements.remove(roomName);
        seenSelfSecretPositions.remove(roomName);
        pendingIgnoredSelfPickups.remove(roomName);
        pendingPotentialSelfSecrets.remove(roomName);
        lastConfirmedBlockSecretAtByRoom.remove(roomName);
        chestSecretPositionsByRoom.remove(roomName);
        pendingChestSecretsByRoom.remove(roomName);
    }

    private static void loadPbs() {
        try {
            if (!Files.exists(PB_FILE)) return;

            String json = new String(Files.readAllBytes(PB_FILE), StandardCharsets.UTF_8);
            Map<String, PbEntry> loaded = GSON.fromJson(json, PB_MAP_TYPE);

            if (loaded != null) {
                personalBests.clear();
                personalBests.putAll(loaded);
            }

            System.out.println("[NSM] Loaded " + personalBests.size() + " secret room PBs.");
        } catch (Throwable throwable) {
            System.out.println("[NSM] Failed to load secret room PBs.");
            throwable.printStackTrace();
        }
    }

    private static void savePbs() {
        try {
            String json = GSON.toJson(personalBests, PB_MAP_TYPE);

            AtomicFiles.writeStringAtomically(PB_FILE, json, StandardCharsets.UTF_8);
        } catch (Throwable throwable) {
            System.out.println("[NSM] Failed to save secret room PBs.");
            throwable.printStackTrace();
        }
    }

    private static void send(String message) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player != null) {
            mc.getInstance().gui.getChat().addClientSystemMessage(Component.literal(message));
        }
    }

    private static String formatDuration(long ms) {
        if (ms < 60_000L) {
            return String.format(Locale.US, "%.2fs", ms / 1000.0);
        }

        long minutes = ms / 60_000L;
        double seconds = (ms % 60_000L) / 1000.0;

        return String.format(Locale.US, "%d:%05.2f", minutes, seconds);
    }

    private static final class Attempt {
        private final String roomName;
        private final long startedAtMs;

        private long lastSecretAtMs;
        private int selfSecrets;
        private int totalSecrets;

        private boolean invalidated;
        private boolean finished;

        private Attempt(String roomName, long startedAtMs, int totalSecrets) {
            this.roomName = roomName;
            this.startedAtMs = startedAtMs;
            this.lastSecretAtMs = startedAtMs;
            this.totalSecrets = totalSecrets;
        }
    }

    private static final class PbEntry {
        private long bestMs;
        private long achievedAtMs;
        private int totalSecrets;
    }

    private static CategoryDungeons.SecretRoomTimer config() {
        return NsmConfig.INSTANCE.dungeons.secretRoomTimer;
    }

    private static boolean enabled() {
        return config().enabled;
    }

    public static void displayAllPbs() {
        if (personalBests.isEmpty()) {
            send("§7[NSM] No secret room PBs saved yet.");
            return;
        }

        List<Map.Entry<String, PbEntry>> entries = new ArrayList<>(personalBests.entrySet());
        entries.sort(Comparator.comparing(entry -> entry.getKey().toLowerCase(Locale.ROOT)));

        send("§a--- Secret Room PBs ---");

        for (Map.Entry<String, PbEntry> entry : entries) {
            PbEntry pb = entry.getValue();

            if (pb == null || pb.bestMs <= 0) continue;

            send("§b" + entry.getKey() +
                    "§7: §e" + formatDuration(pb.bestMs) +
                    " §8(" + pb.totalSecrets + " secrets)");
        }
    }

    public static void resetCurrentRoomPb() {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null) return;

        String roomName = getCurrentRoomName(mc);

        if (roomName == null) {
            send("§c[NSM] You are not in a known dungeon room.");
            return;
        }

        PbEntry removed = personalBests.remove(roomName);

        if (removed == null) {
            send("§7[NSM] No PB existed for §b" + roomName + "§7.");
            return;
        }

        savePbs();

        send("§c[NSM] Reset PB for §b" + roomName + "§c.");
    }

    public static void resetAllPbs() {
        if (personalBests.isEmpty()) {
            send("§7[NSM] No secret room PBs saved yet.");
            return;
        }

        int count = personalBests.size();

        personalBests.clear();
        savePbs();

        send("§c[NSM] Reset §e" + count + "§c secret room PBs.");
    }

    private static boolean consumePendingIgnoredSelfPickup(String roomName, long now) {
        Deque<Long> queue = pendingIgnoredSelfPickups.get(roomName);
        if (queue == null) return false;

        while (!queue.isEmpty() && now - queue.peekFirst() > SELF_SECRET_CONFIRM_WINDOW_MS) {
            queue.removeFirst();
        }

        boolean matched = !queue.isEmpty();

        if (matched) {
            queue.removeFirst();
        }

        if (queue.isEmpty()) {
            pendingIgnoredSelfPickups.remove(roomName);
        }

        return matched;
    }

    public static void onChatMessage(String rawMessage) {
        if (!enabled()) return;
        if (rawMessage == null) return;

        String message = rawMessage.strip();

        if (!isSelfSecretChatMessage(message)) return;

        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            mc.execute(() -> onChatMessage(rawMessage));
            return;
        }

        if (!isDungeonRoomContext(mc, false)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        long now = System.currentTimeMillis();

        if (hasRecentPendingBlockSecret(roomName, now) || wasBlockSecretRecentlyConfirmed(roomName, now)) return;

        boolean matchedEarlierCounterIncrement = consumePendingCounterIncrement(roomName, now);

        if (!matchedEarlierCounterIncrement) {
            pendingSelfPickups
                    .computeIfAbsent(roomName, ignored -> new ArrayDeque<>())
                    .addLast(now);
        }

        countSelfSecret(roomName, now, matchedEarlierCounterIncrement, null);
    }

    private static boolean isSelfSecretChatMessage(String message) {
        return message.contains("You found a Wither Essence!")
                || message.contains("You found an Undead Essence!");
    }

    private static void countSelfSecret(
            String roomName,
            long now,
            boolean matchedEarlierCounterIncrement,
            BlockPos secretPos
    ) {
        int knownFound = knownFoundByRoom.getOrDefault(roomName, 0);
        int foundBeforeThisPickup = knownFound - (matchedEarlierCounterIncrement ? 1 : 0);

        if (foundBeforeThisPickup < 0) {
            foundBeforeThisPickup = 0;
        }

        int knownTotal = knownTotalByRoom.getOrDefault(roomName, -1);

        Attempt attempt = attempts.get(roomName);

        if (attempt == null) {
            if (foundBeforeThisPickup > 0) {
                if (config().showStartMessage) {
                    send("§7[NSM] Not timing §b" + roomName +
                            "§7 because the room already had found secrets.");
                }
                return;
            }

            attempt = new Attempt(roomName, now, knownTotal);
            attempts.put(roomName, attempt);

            if (config().showStartMessage) {
                send("§a[NSM] Timer started for §b" + roomName + "§a.");
            }
        }

        if (attempt.invalidated || attempt.finished) return;

        attempt.selfSecrets++;
        attempt.lastSecretAtMs = now;

        if (knownTotal > 0) {
            attempt.totalSecrets = knownTotal;
        }

        if (config().showProgressMessages) {
            if (attempt.totalSecrets > 0) {
                send("§7[NSM] §b" + roomName + "§7: §e" +
                        attempt.selfSecrets + "§7/§e" + attempt.totalSecrets + " §7secrets.");
            } else {
                send("§7[NSM] §b" + roomName + "§7: §e" +
                        attempt.selfSecrets + " §7secrets.");
            }
        }

        tryFinish(roomName, attempt);
    }

    public static void onPotentialItemSecretPickup(BlockPos itemPos) {
        queuePotentialSecret(itemPos, PotentialSecretKind.ITEM, false);
    }

    public static void onPotentialCombatSecretPickup(BlockPos secretPos) {
        queuePotentialSecret(secretPos, PotentialSecretKind.COMBAT, true);
    }

    public static void onPotentialBlockSecretPickup(BlockPos secretPos) {
        queuePotentialSecret(secretPos, PotentialSecretKind.BLOCK, false);
    }

    private static void queuePotentialSecret(BlockPos secretPos, PotentialSecretKind kind, boolean matchEarlierCounterIncrement) {
        boolean itemSecret = kind == PotentialSecretKind.ITEM;
        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            BlockPos immutablePos = secretPos == null ? null : secretPos.immutable();
            mc.execute(() -> queuePotentialSecret(immutablePos, kind, matchEarlierCounterIncrement));
            return;
        }

        if (!enabled()) return;
        if (!isDungeonRoomContext(mc, true)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;
        if (itemSecret && secretPos == null) return;

        if (itemSecret && isNearRememberedChestSecret(roomName, secretPos)) return;

        Set<Long> seenPositions = seenSelfSecretPositions.get(roomName);
        if (secretPos != null && seenPositions != null && seenPositions.contains(secretPos.asLong())) return;

        long now = System.currentTimeMillis();

        if (matchEarlierCounterIncrement && consumePendingCounterIncrement(roomName, now)) {
            if (secretPos != null) {
                Set<Long> seen = seenSelfSecretPositions.computeIfAbsent(roomName, ignored -> new HashSet<>());
                if (!seen.add(secretPos.asLong())) return;
            }

            PendingPotentialSecret confirmed = new PendingPotentialSecret(
                    secretPos == null ? null : secretPos.immutable(),
                    now,
                    kind
            );
            rememberConfirmedPotentialSecret(roomName, confirmed, now);

            countSelfSecret(roomName, now, true, secretPos);
            return;
        }

        Deque<PendingPotentialSecret> queue = pendingPotentialSelfSecrets
                .computeIfAbsent(roomName, ignored -> new ArrayDeque<>());

        if (secretPos != null) {
            queue.removeIf(pending -> secretPos.equals(pending.pos));
        }
        queue.addLast(new PendingPotentialSecret(secretPos == null ? null : secretPos.immutable(), now, kind));
    }

    private static void rememberConfirmedPotentialSecret(String roomName, PendingPotentialSecret secret, long now) {
        if (secret != null && secret.kind == PotentialSecretKind.BLOCK) {
            lastConfirmedBlockSecretAtByRoom.put(roomName, now);
        }
    }

    private static boolean hasRecentPendingBlockSecret(String roomName, long now) {
        Deque<PendingPotentialSecret> queue = pendingPotentialSelfSecrets.get(roomName);
        if (queue == null || queue.isEmpty()) return false;

        while (!queue.isEmpty() && now - queue.peekFirst().observedAtMs > SELF_SECRET_CONFIRM_WINDOW_MS) {
            queue.removeFirst();
        }

        if (queue.isEmpty()) {
            pendingPotentialSelfSecrets.remove(roomName);
            return false;
        }

        for (PendingPotentialSecret secret : queue) {
            if (secret.kind == PotentialSecretKind.BLOCK) return true;
        }
        return false;
    }

    private static boolean wasBlockSecretRecentlyConfirmed(String roomName, long now) {
        Long confirmedAt = lastConfirmedBlockSecretAtByRoom.get(roomName);
        if (confirmedAt == null) return false;

        if (now - confirmedAt > SELF_SECRET_CONFIRM_WINDOW_MS) {
            lastConfirmedBlockSecretAtByRoom.remove(roomName);
            return false;
        }

        return true;
    }

    public static void onItemSecretPickup(BlockPos itemPos) {
        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            BlockPos immutablePos = itemPos == null ? null : itemPos.immutable();
            mc.execute(() -> onItemSecretPickup(immutablePos));
            return;
        }

        if (!enabled()) return;
        if (!isDungeonRoomContext(mc, true)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        if (isNearRememberedChestSecret(roomName, itemPos)) {
            return;
        }

        onSecretPickup(itemPos);
    }

    public static void onChestSecretPickup(BlockPos chestPos) {
        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            BlockPos immutablePos = chestPos == null ? null : chestPos.immutable();
            mc.execute(() -> onChestSecretPickup(immutablePos));
            return;
        }

        if (!enabled()) return;
        if (!isDungeonRoomContext(mc, true)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null || chestPos == null) return;

        Set<Long> seenPositions = seenSelfSecretPositions.get(roomName);
        if (seenPositions != null && seenPositions.contains(chestPos.asLong())) return;

        Deque<PendingChestSecret> queue = pendingChestSecretsByRoom
                .computeIfAbsent(roomName, ignored -> new ArrayDeque<>());

        // Repeated clicks on the same locked chest must not queue multiple secrets.
        queue.removeIf(pending -> pending.pos.equals(chestPos));
        queue.addLast(new PendingChestSecret(chestPos.immutable(), System.currentTimeMillis()));
    }

    private static void rememberChestSecretPosition(String roomName, BlockPos chestPos) {
        if (roomName == null || chestPos == null) return;

        List<BlockPos> positions = chestSecretPositionsByRoom
                .computeIfAbsent(roomName, ignored -> new ArrayList<>());
        BlockPos immutablePos = chestPos.immutable();
        if (!positions.contains(immutablePos)) positions.add(immutablePos);

        System.out.println("[NSM] Remembered chest secret at " + chestPos + " in room " + roomName);
    }

    private static boolean isNearRememberedChestSecret(String roomName, BlockPos itemPos) {
        if (roomName == null || itemPos == null) return false;

        List<BlockPos> chestPositions = chestSecretPositionsByRoom.get(roomName);
        if (chestPositions == null || chestPositions.isEmpty()) return false;

        for (BlockPos chestPos : chestPositions) {
            if (chestPos.distSqr(itemPos) <= CHEST_DROP_IGNORE_RADIUS_SQ) {
                System.out.println("[NSM] Item pickup at " + itemPos +
                        " ignored because it is near chest secret at " + chestPos +
                        " in room " + roomName);
                return true;
            }
        }

        return false;
    }

    private static PendingChestSecret consumePendingChestSecret(String roomName, long now) {
        Deque<PendingChestSecret> queue = pendingChestSecretsByRoom.get(roomName);
        if (queue == null || queue.isEmpty()) return null;

        while (!queue.isEmpty() && now - queue.peekFirst().clickedAtMs > SELF_SECRET_CONFIRM_WINDOW_MS) {
            queue.removeFirst();
        }

        PendingChestSecret matched = queue.pollLast();
        if (queue.isEmpty()) {
            pendingChestSecretsByRoom.remove(roomName);
        }

        return matched;
    }

    private static PendingPotentialSecret consumePendingPotentialSecret(String roomName, long now) {
        Deque<PendingPotentialSecret> queue = pendingPotentialSelfSecrets.get(roomName);
        if (queue == null || queue.isEmpty()) return null;

        while (!queue.isEmpty() && now - queue.peekFirst().observedAtMs > SELF_SECRET_CONFIRM_WINDOW_MS) {
            queue.removeFirst();
        }

        PendingPotentialSecret matched = queue.pollLast();
        if (queue.isEmpty()) {
            pendingPotentialSelfSecrets.remove(roomName);
        }

        return matched;
    }

    public static int getKnownFoundSecrets(String roomName) {
        if (roomName == null) return -1;

        return knownFoundByRoom.getOrDefault(roomName, -1);
    }

    public static int getKnownTotalSecrets(String roomName) {
        if (roomName == null) return -1;

        return knownTotalByRoom.getOrDefault(roomName, -1);
    }

    public static boolean isRoomSecretCountComplete(String roomName) {
        int found = getKnownFoundSecrets(roomName);
        int total = getKnownTotalSecrets(roomName);

        return total > 0 && found >= total;
    }

    private enum PotentialSecretKind {
        ITEM,
        COMBAT,
        BLOCK
    }

    private static final class PendingPotentialSecret {
        private final BlockPos pos;
        private final long observedAtMs;
        private final PotentialSecretKind kind;

        private PendingPotentialSecret(BlockPos pos, long observedAtMs, PotentialSecretKind kind) {
            this.pos = pos;
            this.observedAtMs = observedAtMs;
            this.kind = kind;
        }
    }

    private static final class PendingChestSecret {
        private final BlockPos pos;
        private final long clickedAtMs;

        private PendingChestSecret(BlockPos pos, long clickedAtMs) {
            this.pos = pos;
            this.clickedAtMs = clickedAtMs;
        }
    }
}
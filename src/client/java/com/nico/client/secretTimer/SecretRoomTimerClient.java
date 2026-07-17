package com.nico.client.secretTimer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.nico.OdinRoomBridge;
import com.nico.client.configuration.NsmConfig;
import com.nico.client.configuration.category.CategoryDungeons;
import com.nico.client.utils.LocationUtils;
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils;
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

    private static final long SELF_SECRET_CONFIRM_WINDOW_MS = 2500L;
    private static final double CHEST_DROP_IGNORE_RADIUS_SQ = 2.0D * 2.0D;
    private static final long LOCKED_CHEST_WAIT_MS = 500L;

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
    private static final Map<String, List<BlockPos>> chestSecretPositionsByRoom = new HashMap<>();
    private static final Map<String, Deque<PendingChestSecret>> pendingChestSecretsByRoom = new HashMap<>();

    private static final Map<String, Set<Long>> seenSelfSecretPositions = new HashMap<>();

    private static boolean lastInDungeonRoom = false;
    private static int tickCounter = 0;

    public static void init() {
        loadPbs();

        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    public static void onRoomSecretsPacket(int foundSecrets, int totalSecrets) {
        if (!enabled()) return;

        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            mc.execute(() -> onRoomSecretsPacket(foundSecrets, totalSecrets));
            return;
        }

        if (!isDungeonRoomContext(mc, false)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        onRoomSecretCounterUpdate(roomName, foundSecrets, totalSecrets);
    }

    public static void onOdinSecretPickup(BlockPos secretPos) {
        System.out.println("[NSM] Stage 1");

        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            BlockPos immutablePos = secretPos == null ? null : secretPos.immutable();
            mc.execute(() -> onOdinSecretPickup(immutablePos));
            return;
        }

        if (!enabled()) return;

        System.out.println("[NSM] Stage 2");

        if (!isDungeonRoomContext(mc, true)) return;

        System.out.println("[NSM] Stage 3");

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        if (shouldIgnoreSecretPickup(mc, secretPos)) {
            pendingIgnoredSelfPickups
                    .computeIfAbsent(roomName, ignored -> new ArrayDeque<>())
                    .addLast(System.currentTimeMillis());

            System.out.println("[NSM] Ignored lever secret pickup at " + secretPos + " in room " + roomName);
            return;
        }

        System.out.println("[NSM] Stage 4");

        long now = System.currentTimeMillis();

        if (secretPos != null) {
            Set<Long> seenPositions = seenSelfSecretPositions.computeIfAbsent(
                    roomName,
                    ignored -> new HashSet<>()
            );

            long posLong = secretPos.asLong();

            System.out.println("[NSM] Stage 5 room=" + roomName
                    + " secretPos=" + secretPos
                    + " posLong=" + posLong
                    + " seenCount=" + seenPositions.size()
                    + " alreadySeen=" + seenPositions.contains(posLong));

            if (!seenPositions.add(secretPos.asLong())) {
                return;
            }
        }

        System.out.println("[NSM] Stage 6");

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
        if (attempt.finished) return;
        if (attempt.invalidated) return;
        if (attempt.totalSecrets <= 0) return;
        if (attempt.selfSecrets < attempt.totalSecrets) return;

        int knownFound = knownFoundByRoom.getOrDefault(roomName, -1);

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
            queue.removeFirst();
            markExternalSecret(roomName);
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
                queue.removeFirst();
                markExternalSecret(roomName);
            }

            if (queue.isEmpty()) {
                iterator.remove();
            }
        }
    }

    private static void markExternalSecret(String roomName) {
        Attempt attempt = attempts.get(roomName);

        if (attempt == null) return;
        if (attempt.finished) return;
        if (attempt.invalidated) return;

        attempt.invalidated = true;

        System.out.println("[NSM] ATTEMPT INVALIDATED for " + roomName);
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

        confirmUnlockedPendingChests(now);

        if (tickCounter % 20 == 0) {
            expireUnmatchedCounterIncrements(now);
        }
    }

    private static boolean isDungeonRoomContext(Minecraft mc, boolean log) {
        if (log) {
            System.out.println("[NSM] DungeonRoomContext");
            System.out.println("[NSM] " + mc.level + " " + mc.level != null);
            System.out.println("[NSM] " + mc.player + " " + mc.player != null);
            System.out.println("[NSM] " + LocationUtils.isInDungeon() + " " + DungeonUtils.INSTANCE.getInDungeons());
            System.out.println("[NSM] " + DungeonUtils.INSTANCE.getInBoss() + " " + DungeonUtils.INSTANCE.getInBoss());
        }
        try {
            return mc.level != null
                    && mc.player != null
                    && LocationUtils.isInDungeon()
                    && !DungeonUtils.INSTANCE.getInBoss();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            return false;
        }
    }

    private static String getCurrentRoomName(Minecraft mc) {
        try {
            if (mc.player == null) return null;

            String roomName = OdinRoomBridge.getRoomNameForPlayer(mc.player);

            if (roomName == null || roomName.isBlank() || roomName.equals("Unknown")) {
                return null;
            }

            return roomName;
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            return null;
        }
    }

    private static void resetRunState() {
        attempts.clear();
        knownFoundByRoom.clear();
        knownTotalByRoom.clear();
        pendingSelfPickups.clear();
        pendingCounterIncrements.clear();
        seenSelfSecretPositions.clear();
        pendingIgnoredSelfPickups.clear();
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
        chestSecretPositionsByRoom.clear();
        pendingChestSecretsByRoom.clear();
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
            Files.createDirectories(PB_FILE.getParent());

            Path tmpFile = PB_FILE.resolveSibling(PB_FILE.getFileName().toString() + ".tmp");
            String json = GSON.toJson(personalBests, PB_MAP_TYPE);

            Files.write(tmpFile, json.getBytes(StandardCharsets.UTF_8));

            try {
                Files.move(
                        tmpFile,
                        PB_FILE,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        tmpFile,
                        PB_FILE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (Throwable throwable) {
            System.out.println("[NSM] Failed to save secret room PBs.");
            throwable.printStackTrace();
        }
    }

    private static void send(String message) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(message), false);
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

    public static void onOdinItemSecretPickup(BlockPos itemPos) {
        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            BlockPos immutablePos = itemPos == null ? null : itemPos.immutable();
            mc.execute(() -> onOdinItemSecretPickup(immutablePos));
            return;
        }

        if (!enabled()) return;
        if (!isDungeonRoomContext(mc, true)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        if (isNearRememberedChestSecret(roomName, itemPos)) {
            return;
        }

        onOdinSecretPickup(itemPos);
    }

    public static void onOdinChestSecretPickup(BlockPos chestPos) {
        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            BlockPos immutablePos = chestPos == null ? null : chestPos.immutable();
            mc.execute(() -> onOdinChestSecretPickup(immutablePos));
            return;
        }

        if (!enabled()) return;
        if (!isDungeonRoomContext(mc, true)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        pendingChestSecretsByRoom
                .computeIfAbsent(roomName, ignored -> new ArrayDeque<>())
                .addLast(new PendingChestSecret(chestPos.immutable(), System.currentTimeMillis()));
    }

    private static void rememberChestSecretPosition(String roomName, BlockPos chestPos) {
        if (roomName == null || chestPos == null) return;

        chestSecretPositionsByRoom
                .computeIfAbsent(roomName, ignored -> new ArrayList<>())
                .add(chestPos.immutable());

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

    private static void confirmUnlockedPendingChests(long now) {
        Iterator<Map.Entry<String, Deque<PendingChestSecret>>> iterator =
                pendingChestSecretsByRoom.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, Deque<PendingChestSecret>> entry = iterator.next();

            String roomName = entry.getKey();
            Deque<PendingChestSecret> queue = entry.getValue();

            while (!queue.isEmpty() && now - queue.peekFirst().clickedAtMs >= LOCKED_CHEST_WAIT_MS) {
                PendingChestSecret pending = queue.removeFirst();

                System.out.println("[NSM] Confirmed unlocked chest secret at " + pending.pos + " in room " + roomName);

                rememberChestSecretPosition(roomName, pending.pos);
                onOdinSecretPickup(pending.pos);
            }

            if (queue.isEmpty()) {
                iterator.remove();
            }
        }
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

    private static final class PendingChestSecret {
        private final BlockPos pos;
        private final long clickedAtMs;

        private PendingChestSecret(BlockPos pos, long clickedAtMs) {
            this.pos = pos;
            this.clickedAtMs = clickedAtMs;
        }
    }
}
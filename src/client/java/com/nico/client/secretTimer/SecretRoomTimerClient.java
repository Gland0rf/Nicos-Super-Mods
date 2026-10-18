package com.nico.client.secretTimer;

import com.nico.client.configuration.NsmConfig;
import com.nico.client.configuration.category.CategoryDungeons;
import com.nico.client.dungeon.DungeonScanner;
import com.nico.client.dungeon.DungeonState;
import com.nico.client.dungeon.DungeonTeammateScanner;
import com.nico.client.utils.LocationUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tracks per-room secret timing from local pickup evidence and the server's authoritative counter.
 * Public methods are event entry points used by packet/Fabric hooks and client commands.
 */
public final class SecretRoomTimerClient {
    private SecretRoomTimerClient() {}

    private static final Map<String, Attempt> attempts = new HashMap<>();
    private static final Map<String, Integer> knownFoundByRoom = new HashMap<>();
    private static final Map<String, Integer> knownTotalByRoom = new HashMap<>();

    private static final SecretPickupCorrelation pickupCorrelation = new SecretPickupCorrelation();

    private static boolean lastInDungeonRoom = false;
    private static int tickCounter = 0;
    private static boolean initialized = false;

    public static synchronized void init() {
        if (initialized) return;

        SecretRoomPersonalBests.load();
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        initialized = true;
    }

    public static void onRoomSecretsPacket(int foundSecrets, int totalSecrets) {
        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            mc.execute(() -> onRoomSecretsPacket(foundSecrets, totalSecrets));
            return;
        }

        if (!isDungeonRoomContext(mc)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        // Room stacking relies on the authoritative room counters even when PB timing is disabled.
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

        if (!isDungeonRoomContext(mc)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        if (shouldIgnoreSecretPickup(mc, secretPos)) {
            long now = System.currentTimeMillis();
            pickupCorrelation.recordIgnoredSelfPickup(roomName, now);

            System.out.println("[NSM] Ignored lever secret pickup at " + secretPos + " in room " + roomName);
            return;
        }

        long now = System.currentTimeMillis();

        if (secretPos != null && !pickupCorrelation.markPositionSeen(roomName, secretPos)) return;

        boolean matchedEarlierCounterIncrement = consumePendingCounterIncrement(roomName, now);

        if (!matchedEarlierCounterIncrement) {
            pickupCorrelation.recordSelfPickup(roomName, now);
        }

        countSelfSecret(roomName, now, matchedEarlierCounterIncrement);
    }

    public static void onRoomSecretCounterUpdate(String roomName, int foundSecrets, int totalSecrets) {
        if (roomName == null || roomName.isBlank() || roomName.equals("Unknown")) return;

        long now = System.currentTimeMillis();

        updateKnownTotal(roomName, totalSecrets);

        Integer previousFound = knownFoundByRoom.get(roomName);

        if (previousFound == null) {
            knownFoundByRoom.put(roomName, foundSecrets);

            processCounterIncrements(roomName, foundSecrets, now);

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
        processCounterIncrements(roomName, delta, now);
    }

    private static void updateKnownTotal(String roomName, int totalSecrets) {
        if (totalSecrets <= 0) return;

        knownTotalByRoom.put(roomName, totalSecrets);
        Attempt attempt = attempts.get(roomName);
        if (attempt != null) {
            attempt.totalSecrets = totalSecrets;
        }
    }

    private static void processCounterIncrements(String roomName, int count, long now) {
        for (int i = 0; i < count; i++) {
            if (!matchPendingSelfSecret(roomName, now)) {
                pickupCorrelation.addPendingCounterIncrement(roomName, now);
            }
        }

        pickupCorrelation.expireCounterIncrements(now, SecretRoomTimerClient::resolveUnmatchedCounterIncrement);

        Attempt attempt = attempts.get(roomName);
        if (attempt != null) {
            tryFinish(roomName, attempt);
        }
    }

    private static boolean matchPendingSelfSecret(String roomName, long now) {
        BlockPos chestPos = pickupCorrelation.consumePendingChestSecret(roomName, now);
        if (chestPos != null) {
            if (pickupCorrelation.markPositionSeen(roomName, chestPos)) {
                pickupCorrelation.rememberChestSecretPosition(roomName, chestPos);
                countSelfSecret(roomName, now, true);
            }
            return true;
        }

        SecretPickupCorrelation.PotentialSecret potentialSecret =
                pickupCorrelation.consumePendingPotentialSecret(roomName, now);
        if (potentialSecret != null) {
            pickupCorrelation.rememberConfirmedPotentialSecret(roomName, potentialSecret, now);
            BlockPos position = potentialSecret.position();
            if (position == null || pickupCorrelation.markPositionSeen(roomName, position)) {
                countSelfSecret(roomName, now, true);
            }
            return true;
        }

        return pickupCorrelation.consumePendingSelfPickup(roomName, now)
                || pickupCorrelation.consumePendingIgnoredSelfPickup(roomName, now);
    }

    public static void onLockedChestMessage() {
        if (!enabled()) return;

        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            mc.execute(SecretRoomTimerClient::onLockedChestMessage);
            return;
        }

        if (!isDungeonRoomContext(mc)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        BlockPos removed = pickupCorrelation.removeLastPendingChest(roomName);
        if (removed == null) return;

        System.out.println("[NSM] Removed locked chest pending secret at " + removed + " in room " + roomName);
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

        if (knownFound >= 0 && knownFound < attempt.totalSecrets) {
            return;
        }

        attempt.finished = true;

        long durationMs = Math.max(0L, attempt.lastSecretAtMs - attempt.startedAtMs);

        SecretRoomPersonalBests.Entry oldPb = SecretRoomPersonalBests.get(roomName);
        boolean newPb = oldPb == null || oldPb.bestMs() <= 0 || durationMs < oldPb.bestMs();

        if (newPb) {
            SecretRoomPersonalBests.save(roomName, durationMs, attempt.totalSecrets);

            if (config().showCompletionMessage) {
                String previousText = oldPb == null || oldPb.bestMs() <= 0
                        ? ""
                        : " §7Previous: §e" + formatDuration(oldPb.bestMs());

                send("§6§l[NSM] NEW PB §b" + roomName +
                        "§6: §e" + formatDuration(durationMs) +
                        " §7(" + attempt.totalSecrets + " secrets)" +
                        previousText);
            }
        } else {
            if (config().showCompletionMessage && !config().onlyAnnounceNewPbs) {
                send("§6[NSM] Completed §b" + roomName +
                        "§6 in §e" + formatDuration(durationMs) +
                        " §7PB: §e" + formatDuration(oldPb.bestMs()));
            }
        }

        attempts.remove(roomName);
    }

    private static boolean consumePendingCounterIncrement(String roomName, long now) {
        return pickupCorrelation.consumePendingCounterIncrement(
                roomName,
                now,
                SecretRoomTimerClient::resolveUnmatchedCounterIncrement
        );
    }

    private static void resolveUnmatchedCounterIncrement(String roomName, long observedAtMs) {
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

            for (Player player : mc.level.players()) {
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
            attempt = new Attempt(observedAtMs, knownTotal);
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
        boolean inDungeonRoom = isDungeonRoomContext(mc);

        if (lastInDungeonRoom && !inDungeonRoom) {
            resetRunState();
        }

        lastInDungeonRoom = inDungeonRoom;

        long now = System.currentTimeMillis();

        if (tickCounter % 20 == 0) {
            pickupCorrelation.expireCounterIncrements(now, SecretRoomTimerClient::resolveUnmatchedCounterIncrement);
        }
    }

    private static boolean isDungeonRoomContext(Minecraft mc) {
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
        pickupCorrelation.clear();
    }

    private static void resetRoomState(String roomName) {
        attempts.remove(roomName);
        knownFoundByRoom.remove(roomName);
        knownTotalByRoom.remove(roomName);
        pickupCorrelation.clearRoom(roomName);
    }

    private static void send(String message) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player != null) {
            mc.gui.hud.getChat().addClientSystemMessage(Component.literal(message));
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
        private final long startedAtMs;

        private long lastSecretAtMs;
        private int selfSecrets;
        private int totalSecrets;

        private boolean invalidated;
        private boolean finished;

        private Attempt(long startedAtMs, int totalSecrets) {
            this.startedAtMs = startedAtMs;
            this.lastSecretAtMs = startedAtMs;
            this.totalSecrets = totalSecrets;
        }
    }

    private static CategoryDungeons.SecretRoomTimer config() {
        return NsmConfig.INSTANCE.dungeons.secretRoomTimer;
    }

    private static boolean enabled() {
        return config().enabled;
    }

    public static void displayAllPbs() {
        if (SecretRoomPersonalBests.isEmpty()) {
            send("§7[NSM] No secret room PBs saved yet.");
            return;
        }

        send("§a--- Secret Room PBs ---");

        for (Map.Entry<String, SecretRoomPersonalBests.Entry> entry : SecretRoomPersonalBests.sortedEntries()) {
            SecretRoomPersonalBests.Entry pb = entry.getValue();

            if (pb == null || pb.bestMs() <= 0) continue;

            send("§b" + entry.getKey() +
                    "§7: §e" + formatDuration(pb.bestMs()) +
                    " §8(" + pb.totalSecrets() + " secrets)");
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

        SecretRoomPersonalBests.Entry removed = SecretRoomPersonalBests.remove(roomName);

        if (removed == null) {
            send("§7[NSM] No PB existed for §b" + roomName + "§7.");
            return;
        }

        send("§c[NSM] Reset PB for §b" + roomName + "§c.");
    }

    public static void resetAllPbs() {
        if (SecretRoomPersonalBests.isEmpty()) {
            send("§7[NSM] No secret room PBs saved yet.");
            return;
        }

        int count = SecretRoomPersonalBests.clear();

        send("§c[NSM] Reset §e" + count + "§c secret room PBs.");
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

        if (!isDungeonRoomContext(mc)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        long now = System.currentTimeMillis();

        if (pickupCorrelation.hasRecentPendingBlockSecret(roomName, now)
                || pickupCorrelation.wasBlockSecretRecentlyConfirmed(roomName, now)) {
            return;
        }

        boolean matchedEarlierCounterIncrement = consumePendingCounterIncrement(roomName, now);

        if (!matchedEarlierCounterIncrement) {
            pickupCorrelation.recordSelfPickup(roomName, now);
        }

        countSelfSecret(roomName, now, matchedEarlierCounterIncrement);
    }

    private static boolean isSelfSecretChatMessage(String message) {
        return message.contains("You found a Wither Essence!")
                || message.contains("You found an Undead Essence!");
    }

    private static void countSelfSecret(
            String roomName,
            long now,
            boolean matchedEarlierCounterIncrement
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

            attempt = new Attempt(now, knownTotal);
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
        queuePotentialSecret(itemPos, SecretPickupCorrelation.PotentialSecretKind.ITEM, false);
    }

    public static void onPotentialCombatSecretPickup(BlockPos secretPos) {
        queuePotentialSecret(secretPos, SecretPickupCorrelation.PotentialSecretKind.COMBAT, true);
    }

    public static void onPotentialBlockSecretPickup(BlockPos secretPos) {
        queuePotentialSecret(secretPos, SecretPickupCorrelation.PotentialSecretKind.BLOCK, false);
    }

    private static void queuePotentialSecret(
            BlockPos secretPos,
            SecretPickupCorrelation.PotentialSecretKind kind,
            boolean matchEarlierCounterIncrement
    ) {
        boolean itemSecret = kind == SecretPickupCorrelation.PotentialSecretKind.ITEM;
        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            BlockPos immutablePos = secretPos == null ? null : secretPos.immutable();
            mc.execute(() -> queuePotentialSecret(immutablePos, kind, matchEarlierCounterIncrement));
            return;
        }

        if (!enabled()) return;
        if (!isDungeonRoomContext(mc)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;
        if (itemSecret && secretPos == null) return;

        if (itemSecret && pickupCorrelation.isNearRememberedChestSecret(roomName, secretPos)) return;
        if (pickupCorrelation.hasSeenPosition(roomName, secretPos)) return;

        long now = System.currentTimeMillis();

        if (matchEarlierCounterIncrement && consumePendingCounterIncrement(roomName, now)) {
            if (secretPos != null && !pickupCorrelation.markPositionSeen(roomName, secretPos)) return;

            pickupCorrelation.rememberConfirmedPotentialSecret(roomName, kind, now);

            countSelfSecret(roomName, now, true);
            return;
        }

        pickupCorrelation.queuePotentialSecret(roomName, secretPos, kind, now);
    }

    public static void onItemSecretPickup(BlockPos itemPos) {
        Minecraft mc = Minecraft.getInstance();

        if (!mc.isSameThread()) {
            BlockPos immutablePos = itemPos == null ? null : itemPos.immutable();
            mc.execute(() -> onItemSecretPickup(immutablePos));
            return;
        }

        if (!enabled()) return;
        if (!isDungeonRoomContext(mc)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null) return;

        if (pickupCorrelation.isNearRememberedChestSecret(roomName, itemPos)) {
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
        if (!isDungeonRoomContext(mc)) return;

        String roomName = getCurrentRoomName(mc);
        if (roomName == null || chestPos == null) return;

        if (pickupCorrelation.hasSeenPosition(roomName, chestPos)) return;

        pickupCorrelation.queueChestSecret(roomName, chestPos, System.currentTimeMillis());
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
}
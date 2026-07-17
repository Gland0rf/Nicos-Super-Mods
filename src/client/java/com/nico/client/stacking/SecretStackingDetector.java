package com.nico.client.stacking;

import com.nico.OdinRoomBridge;
import com.nico.client.configuration.NsmConfig;
import com.nico.client.secretTimer.SecretRoomTimerClient;
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public final class SecretStackingDetector {

    private static final long SELF_SECRET_CONFIRM_WINDOW_MS = 2_500;
    private static final long STACKING_WINDOW_MS = 15_000;
    private static final long ALERT_COOLDOWN_MS = 5_000;

    private static String currentRoomName;
    private static int lastRoomSecretCount = -1;

    private static long lastSelfSecretAt;
    private static String lastSelfSecretRoom;

    private static long pendingSelfSecretAt;
    private static String pendingSelfSecretRoom;

    private static long lastAlertAt;

    private SecretStackingDetector() {
    }

    public static void onRoomSecretsPacket(
            int foundSecrets,
            int totalSecrets
    ) {
        SecretRoomTimerClient.onRoomSecretsPacket(
                foundSecrets,
                totalSecrets
        );

        if (!isDetectorEnabled()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        if (!isValidDungeonState(minecraft)) {
            return;
        }

        String roomName = getCurrentPlayerRoom(minecraft);

        if (!isKnownRoom(roomName)) {
            return;
        }

        onRoomSecretCounterUpdate(roomName, foundSecrets);
    }

    public static void onRoomChanged() {
        currentRoomName = null;
        lastRoomSecretCount = -1;

        clearPendingSelfSecret();
    }

    public static void onOdinSecretPickup(BlockPos secretPos) {
        SecretRoomTimerClient.onOdinSecretPickup(secretPos);

        Minecraft minecraft = Minecraft.getInstance();

        if (!isValidDungeonState(minecraft)) {
            return;
        }

        String roomName = getCurrentPlayerRoom(minecraft);

        if (!isKnownRoom(roomName)) {
            return;
        }

        long now = System.currentTimeMillis();

        lastSelfSecretAt = now;
        lastSelfSecretRoom = roomName;

        pendingSelfSecretAt = now;
        pendingSelfSecretRoom = roomName;
    }

    public static void onRoomSecretCounterUpdate(
            String roomName,
            int newSecretCount
    ) {
        Minecraft minecraft = Minecraft.getInstance();

        if (!isValidDungeonState(minecraft) || !isKnownRoom(roomName)) {
            return;
        }

        long now = System.currentTimeMillis();

        if (hasEnteredNewRoom(roomName)) {
            startTrackingRoom(roomName, newSecretCount);
            return;
        }

        if (lastRoomSecretCount < 0) {
            lastRoomSecretCount = newSecretCount;
            return;
        }

        int delta = newSecretCount - lastRoomSecretCount;
        lastRoomSecretCount = newSecretCount;

        if (delta <= 0) {
            return;
        }

        processSecretIncrements(roomName, delta, now);
    }

    private static void processSecretIncrements(
            String roomName,
            int delta,
            long now
    ) {
        for (int index = 0; index < delta; index++) {
            handleRoomSecretIncrement(roomName, now);
        }
    }

    private static void handleRoomSecretIncrement(
            String roomName,
            long now
    ) {
        if (isProbablyOurOwnSecret(roomName, now)) {
            clearPendingSelfSecret();
            debug("Room counter increment was probably ours.");
            return;
        }

        if (didWeRecentlyGetSecretInRoom(roomName, now)) {
            onStackingDetected(roomName);
            return;
        }

        debug("Someone else got a secret in this room, but not stacking.");
    }

    private static boolean isProbablyOurOwnSecret(
            String roomName,
            long now
    ) {
        return pendingSelfSecretRoom != null
                && pendingSelfSecretRoom.equals(roomName)
                && now - pendingSelfSecretAt
                <= SELF_SECRET_CONFIRM_WINDOW_MS;
    }

    private static boolean didWeRecentlyGetSecretInRoom(
            String roomName,
            long now
    ) {
        return lastSelfSecretRoom != null
                && lastSelfSecretRoom.equals(roomName)
                && now - lastSelfSecretAt <= STACKING_WINDOW_MS;
    }

    private static void onStackingDetected(String roomName) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastAlertAt < ALERT_COOLDOWN_MS) {
            return;
        }

        lastAlertAt = now;

        minecraft.player.displayClientMessage(
                Component.literal(
                        "§c§l[NSM] Stacking detected in §b"
                                + roomName
                                + "§c!"
                ),
                false
        );

        System.out.println(
                "[NSM] Stacking detected in " + roomName
        );
    }

    private static boolean isDetectorEnabled() {
        return NsmConfig.INSTANCE
                .dungeons
                .secretStackingDetectorEnabled;
    }

    private static boolean isValidDungeonState(
            Minecraft minecraft
    ) {
        return minecraft.level != null
                && minecraft.player != null
                && DungeonUtils.INSTANCE.getInDungeons()
                && !DungeonUtils.INSTANCE.getInBoss();
    }

    private static String getCurrentPlayerRoom(
            Minecraft minecraft
    ) {
        return OdinRoomBridge.getRoomNameForPlayer(
                minecraft.player
        );
    }

    private static boolean isKnownRoom(String roomName) {
        return roomName != null && !roomName.equals("Unknown");
    }

    private static boolean hasEnteredNewRoom(String roomName) {
        return !roomName.equals(currentRoomName);
    }

    private static void startTrackingRoom(
            String roomName,
            int secretCount
    ) {
        currentRoomName = roomName;
        lastRoomSecretCount = secretCount;

        clearPendingSelfSecret();
    }

    private static void clearPendingSelfSecret() {
        pendingSelfSecretAt = 0;
        pendingSelfSecretRoom = null;
    }

    private static void debug(String message) {
        System.out.println("[NSM] " + message);
    }
}
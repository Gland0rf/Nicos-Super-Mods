package com.nico.client.stacking;

import com.nico.OdinRoomBridge;
import com.nico.client.NsmClientCommands;
import com.nico.client.SecretStackTrackerClient;
import com.nico.client.configuration.NsmConfig;
import com.nico.client.secretTimer.SecretRoomTimerClient;
import com.nico.client.utils.LocationUtils;
import com.odtheking.odin.utils.skyblock.dungeon.DungeonClass;
import com.odtheking.odin.utils.skyblock.dungeon.DungeonPlayer;
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Player;

import java.util.*;

public class RoomStackingDetector {

    public static final long ROOM_STACK_ALERT_COOLDOWN_MS = 5000L;

    private static final int SCORE_SAME_ROOM_ONCE = 60;
    private static final int SCORE_EVERY_5_SECONDS = 30;
    private static final int SCORE_GLOBAL_SECRET_INCREASE = 5;
    private static final int ALERT_THRESHOLD = 100;

    private static final long SCORE_TIME_INTERVAL_MS = 5000L;

    private static long lastGlobalAlertAt = 0L;
    private static int lastKnownGlobalSecretCount = -1;

    private static final Map<String, Long> lastAlertByRoom = new HashMap<>();
    private static final Map<String, RoomStackState> roomStates = new HashMap<>();

    private RoomStackingDetector() { }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null || mc.player == null) {
            resetAllState();
            return;
        }
        if (!LocationUtils.isInDungeon()) {
            resetAllState();
            return;
        }
        if (DungeonUtils.INSTANCE.getInBoss()) {
            resetAllState();
            return;
        }

        if (isSecretCountAtOrAboveConfiguredPercent()) {
            resetAllState();
            return;
        }

        int globalSecretDelta = getGlobalSecretDelta();
        long now = System.currentTimeMillis();

        Map<String, List<Player>> playersByRoom = getDungeonPlayersByRoom(mc);
        Set<String> activeRoomsThisTick = new HashSet<>();

        for (Map.Entry<String, List<Player>> entry : playersByRoom.entrySet()) {
            String roomName = entry.getKey();
            List<Player> players = entry.getValue();

            if (shouldDisregardRoom(roomName, players)) {
                resetRoomState(roomName);
                continue;
            }

            activeRoomsThisTick.add(roomName);

            RoomStackState state = roomStates.computeIfAbsent(
                    roomName,
                    ignored -> new RoomStackState()
            );

            mc.player.displayClientMessage(Component.literal(state.score + " score"), false);

            updateRoomScore(state, roomName, players, globalSecretDelta, now);

            if (state.score > ALERT_THRESHOLD) {
                trySendAlert(roomName, players, state.score, now);
            }
        }

        removeInactiveRoomStates(activeRoomsThisTick);
     }

     private static boolean shouldDisregardRoom(String roomName, List<Player> players) {
        if (roomName == null || roomName.isBlank() || roomName.equals("Unknown")) {
            return true;
        }

        if (isStartRoom(roomName) || isFairyRoom(roomName)) {
            return true;
        }

        if (players == null || players.size() < 2) { // CHANGE THIS AFTER
            return true;
        }

        if (hasLockedWitherDoor(players)) {
            return true;
        }

        return isRoomFullyComplete(roomName);
     }

    private static boolean isStartRoom(String roomName) {
        return roomName.equalsIgnoreCase("Entrance");
    }

    private static boolean isFairyRoom(String roomName) {
        return roomName.equalsIgnoreCase("Fairy");
    }

    private static boolean isRoomFullyComplete(String roomName) {
        if (!SecretRoomTimerClient.isRoomSecretCountComplete(roomName)) {
            return false;
        }

        // CHECK IF ROOM IS CLEARED HERE

        return true;
    }

    private static void updateRoomScore(
            RoomStackState state,
            String roomName,
            List<Player> players,
            int globalSecretDelta,
            long now
    ) {
        Set<String> currentPlayers = getPlayerNameSet(players);

        if (state.playerNames.isEmpty()) {
            state.playerNames.addAll(currentPlayers);
            state.lastTimeScoreAtMs = now;
        } else if (!state.playerNames.equals(currentPlayers)) {
            state.playerNames.clear();
            state.playerNames.addAll(currentPlayers);
            state.lastTimeScoreAtMs = now;
        }

        if (!state.addedSameRoomScore) {
            state.score += SCORE_SAME_ROOM_ONCE;
            state.addedSameRoomScore = true;
        }

        while (now - state.lastTimeScoreAtMs >= SCORE_TIME_INTERVAL_MS) {
            state.score += SCORE_EVERY_5_SECONDS;
            state.lastTimeScoreAtMs += SCORE_TIME_INTERVAL_MS;
        }

        if (globalSecretDelta > 0) {
            state.score += globalSecretDelta * SCORE_GLOBAL_SECRET_INCREASE;
        }

        if (state.score < 0) {
            state.score = 0;
        }
    }

    private static boolean trySendAlert(String roomName, List<Player> players, int score, long now) {
        Long lastForRoom = lastAlertByRoom.get(roomName);

        if (lastForRoom != null && now - lastForRoom < ROOM_STACK_ALERT_COOLDOWN_MS) {
            return false;
        }

        if (now - lastGlobalAlertAt < 1000L) {
            return false;
        }

        lastGlobalAlertAt = now;
        lastAlertByRoom.put(roomName, now);

        onRoomStackingDetected(roomName, players, score);
        return true;
    }

    private static int getGlobalSecretDelta() {
        int current = DungeonUtils.INSTANCE.getSecretCount();

        if (lastKnownGlobalSecretCount < 0) {
            lastKnownGlobalSecretCount = current;
            return 0;
        }

        if (current < lastKnownGlobalSecretCount) {
            lastKnownGlobalSecretCount = current;
            return 0;
        }

        int delta = current - lastKnownGlobalSecretCount;
        lastKnownGlobalSecretCount = current;

        return delta;
    }

    private static void removeInactiveRoomStates(Set<String> activeRoomsThisTick) {
        Iterator<String> iterator = roomStates.keySet().iterator();

        while (iterator.hasNext()) {
            String roomName = iterator.next();

            if (!activeRoomsThisTick.contains(roomName)) {
                iterator.remove();
                lastAlertByRoom.remove(roomName);
            }
        }
    }

    private static void resetRoomState(String roomName) {
        roomStates.remove(roomName);
        lastAlertByRoom.remove(roomName);
    }

    private static void resetAllState() {
        roomStates.clear();
        lastAlertByRoom.clear();
        lastGlobalAlertAt = 0L;
        lastKnownGlobalSecretCount = -1;
    }

     private static Map<String, List<Player>> getDungeonPlayersByRoom(Minecraft mc) {
        Map<String, List<Player>> playersByRoom = new HashMap<>();
        Set<String> teammateNames = NsmClientCommands.getOdinDungeonTeammateNames();

        for (Player player : mc.level.players()) {
            String playerName = player.getName().getString();

            boolean isSelf = player == mc.player;
            boolean isTeammate = teammateNames.contains(playerName);

            if (!isSelf && !isTeammate) continue;

            String roomName = OdinRoomBridge.getRoomNameForPlayer(player);

            if (roomName == null || roomName.equals("Unknown")) continue;

            playersByRoom
                    .computeIfAbsent(roomName, k -> new ArrayList<>())
                    .add(player);
        }

        return playersByRoom;
     }

     private static boolean isSecretCountAtOrAboveConfiguredPercent() {
        int found = DungeonUtils.INSTANCE.getSecretCount();
        int total = DungeonUtils.INSTANCE.getTotalSecrets();

        if (total <= 0) return false;

        double configuredPercent = NsmConfig.INSTANCE.dungeons.roomStacking.disableRoomStackingAtSecretPercent / 100.0;

        return found / (double) total >= configuredPercent;
     }

    private static Set<String> getPlayerNameSet(List<Player> players) {
        Set<String> names = new TreeSet<>();

        for (Player player : players) {
            names.add(player.getName().getString());
        }

        return names;
    }

     private static boolean hasLockedWitherDoor(List<Player> players) {
        for (Player player : players) {
            if (OdinRoomBridge.hasLockedWitherDoorForPlayer(player)) {
                return true;
            }
        }

        return false;
     }

     private static void onRoomStackingDetected(String roomName, List<Player> players, int score) {
        Minecraft mc = Minecraft.getInstance();

        if (mc.player == null) return;

         String allNames = NsmConfig.INSTANCE.dungeons.roomStacking.includeClassesInChat
                    ? getPlayerNamesWithClasses(players)
                    : getPlayerNames(players);

         String otherNames = getOtherPlayerNames(players, mc.player);

         boolean includesSelf = players.stream().anyMatch(player ->
                 player == mc.player || player.getName().getString().equals(mc.player.getName().getString())
         );

         if (includesSelf) {
             if (NsmConfig.INSTANCE.dungeons.roomStacking.showSelfTitleAlert) {
                 mc.gui.setTimes(10, 35, 20);

                 mc.gui.setTitle(
                         Component.literal("You are stacking!")
                                 .withStyle(ChatFormatting.RED)
                 );

                 mc.gui.setSubtitle(
                         Component.literal("With " + otherNames)
                 );
             }

             if (NsmConfig.INSTANCE.dungeons.roomStacking.playAlertSounds) {
                 mc.player.playSound(
                         SoundEvents.BELL_BLOCK,
                         1.0F,
                         1.0F
                 );
             }
         } else {
             if (NsmConfig.INSTANCE.dungeons.roomStacking.showOtherStackingChatAlert) {
                 mc.player.displayClientMessage(
                         Component.literal("[NSM] ")
                                 .withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                                 .append(Component.literal(allNames + " are stacking in " + roomName + ".")
                                         .withStyle(ChatFormatting.RED)),
                         false
                 );
             }

             if (NsmConfig.INSTANCE.dungeons.roomStacking.playAlertSounds) {
                 mc.player.playSound(
                         SoundEvents.NOTE_BLOCK_BELL.value(),
                         1.0F,
                         1.0F
                 );
             }
         }

         if (mc.player != null) {
             mc.player.playSound(
                     SoundEvents.BELL_BLOCK, // newer versions
                     1.0F, // volume
                     1.0F  // pitch
             );
         }
    }

    private static String getPlayerNames(List<Player> players) {
        List<String> names = new ArrayList<>();

        for (Player player : players) {
            names.add(player.getName().getString());
        }

        Collections.sort(names);

        return String.join(", ", names);
    }

    private static String getOtherPlayerNames(List<Player> players, Player self) {
        List<String> names = new ArrayList<>();

        for (Player player : players) {
            if (player == self) continue;

            String name = player.getName().getString();

            if (name.equals(self.getName().getString())) continue;

            names.add(name);
        }

        Collections.sort(names);

        if (names.isEmpty()) {
            return "nobody";
        }

        return String.join(", ", names);
    }

    private static String getPlayerNamesWithClasses(List<Player> players) {
        List<String> names = new ArrayList<>();

        for (Player player : players) {
            String name = player.getName().getString();
            String clazz = getDungeonClassForPlayer(player);

            names.add(name + " (" + clazz + ")");
        }

        Collections.sort(names);

        return String.join(", ", names);
    }

    private static String getDungeonClassForPlayer(Player player) {
        String playerName = player.getName().getString();

        try {
            for (DungeonPlayer dungeonPlayer : DungeonUtils.INSTANCE.getDungeonTeammates()) {
                if (!dungeonPlayer.getName().equals(playerName)) continue;

                DungeonClass clazz = dungeonPlayer.getClazz();

                if (clazz == null || clazz == DungeonClass.Unknown) {
                    return "Unknown";
                }

                return formatClassName(clazz.name());
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

        return "Unknown";
    }

    private static String formatClassName(String name) {
        if (name == null || name.isEmpty()) {
            return "Unknown";
        }

        name = name.toLowerCase(Locale.ROOT);

        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private static final class RoomStackState {
        private final Set<String> playerNames = new TreeSet<>();

        private int score = 0;
        private boolean addedSameRoomScore = false;
        private long lastTimeScoreAtMs = 0L;
    }

}

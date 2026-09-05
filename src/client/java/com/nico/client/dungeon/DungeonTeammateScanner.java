package com.nico.client.dungeon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DungeonTeammateScanner {

    private static final Map<String, String> CLASS_BY_PLAYER =
            new HashMap<>();

    private static final Pattern DUNGEON_PLAYER_PATTERN = Pattern.compile(
            "^\\[\\d+]\\s+"
                        + "(?:\\[[^]]+]\\s+)*"
                        + "(?<name>[A-Za-z0-9_]{1,16})"
                        + ".*?"
                        + "\\((?<clazz>[A-Za-z]+)(?:\\s+[IVXLCDM]+)?\\)"
                        + "$"
    );

    private static final Set<String> DUNGEON_CLASSES = Set.of(
            "ARCHER",
            "BERSERK",
            "HEALER",
            "MAGE",
            "TANK",
            "DEAD"
    );

    private DungeonTeammateScanner() { }

    public static void clearTransientState() {
        CLASS_BY_PLAYER.clear();
    }

    public static void reset() {
        CLASS_BY_PLAYER.clear();
    }

    public static Set<String> getDungeonTeammateNames() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();

        if (connection == null) return Set.of();

        Set<String> names = new LinkedHashSet<>();
        for (PlayerInfo playerInfo : connection.getOnlinePlayers()) {
            Component displayName = playerInfo.getTabListDisplayName();
            if (displayName == null) continue;

            String line = displayName.getString().trim();

            Matcher matcher = DUNGEON_PLAYER_PATTERN.matcher(line);
            if (!matcher.matches()) continue;

            String dungeonClass = matcher.group("clazz").toUpperCase(Locale.ROOT);
            if (!DUNGEON_CLASSES.contains(dungeonClass)) continue;

            names.add(matcher.group("name"));
        }

        return names;
    }

    public static String getDungeonClassForPlayer(Player player) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();

        if (connection == null) {
            return "Unknown";
        }

        String wantedName = player.getName().getString();

        for (PlayerInfo playerInfo : connection.getOnlinePlayers()) {
            Component displayName = playerInfo.getTabListDisplayName();

            if (displayName == null) {
                continue;
            }

            String line = displayName.getString().trim();

            Matcher matcher = DUNGEON_PLAYER_PATTERN.matcher(line);

            if (!matcher.matches()) {
                continue;
            }

            String name = matcher.group("name");

            if (!name.equalsIgnoreCase(wantedName)) {
                continue;
            }

            String clazz = matcher.group("clazz");

            if (clazz == null || clazz.isBlank()) {
                return CLASS_BY_PLAYER.getOrDefault(
                        wantedName.toLowerCase(Locale.ROOT),
                        "Unknown"
                );
            }

            /*
             * Hypixel displays DEAD instead of the player's class
             * after they die. Keep the class we saw previously.
             */
            if (clazz.equalsIgnoreCase("DEAD")) {
                return CLASS_BY_PLAYER.getOrDefault(
                        wantedName.toLowerCase(Locale.ROOT),
                        "Unknown"
                );
            }

            String formatted = formatClassName(clazz);

            CLASS_BY_PLAYER.put(
                    wantedName.toLowerCase(Locale.ROOT),
                    formatted
            );

            return formatted;
        }

        return CLASS_BY_PLAYER.getOrDefault(
                wantedName.toLowerCase(Locale.ROOT),
                "Unknown"
        );
    }

    private static String formatClassName(String clazz) {
        if (clazz == null || clazz.isBlank()) {
            return "Unknown";
        }

        String lower = clazz.toLowerCase(Locale.ROOT);

        return Character.toUpperCase(lower.charAt(0))
                + lower.substring(1);
    }
}

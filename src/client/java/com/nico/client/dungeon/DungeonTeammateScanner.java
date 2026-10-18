package com.nico.client.dungeon;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads dungeon teammate names and classes from Hypixel's tab list. */
public final class DungeonTeammateScanner {

    private static final Map<String, String> CLASS_BY_PLAYER = new HashMap<>();

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

    public static Set<String> getDungeonTeammateNames() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) return Set.of();

        Set<String> names = new LinkedHashSet<>();
        for (PlayerInfo playerInfo : connection.getOnlinePlayers()) {
            DungeonPlayer entry = parseDungeonPlayer(playerInfo);
            if (entry != null && DUNGEON_CLASSES.contains(entry.dungeonClass())) {
                names.add(entry.name());
            }
        }

        return names;
    }

    public static String getDungeonClassForPlayer(Player player) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        String wantedName = player.getName().getString();
        String cacheKey = wantedName.toLowerCase(Locale.ROOT);

        if (connection == null) {
            return "Unknown";
        }

        for (PlayerInfo playerInfo : connection.getOnlinePlayers()) {
            DungeonPlayer entry = parseDungeonPlayer(playerInfo);
            if (entry == null || !entry.name().equalsIgnoreCase(wantedName)) continue;

            // Hypixel displays DEAD instead of the player's class after they die.
            // Keep the last class we saw rather than replacing it with DEAD.
            if (entry.dungeonClass().equalsIgnoreCase("DEAD")) {
                return CLASS_BY_PLAYER.getOrDefault(cacheKey, "Unknown");
            }

            String formattedClass = formatClassName(entry.dungeonClass());
            CLASS_BY_PLAYER.put(cacheKey, formattedClass);
            return formattedClass;
        }

        return CLASS_BY_PLAYER.getOrDefault(cacheKey, "Unknown");
    }


    private static DungeonPlayer parseDungeonPlayer(PlayerInfo playerInfo) {
        Component displayName = playerInfo.getTabListDisplayName();
        if (displayName == null) return null;

        Matcher matcher = DUNGEON_PLAYER_PATTERN.matcher(displayName.getString().trim());
        if (!matcher.matches()) return null;

        String dungeonClass = matcher.group("clazz").toUpperCase(Locale.ROOT);
        return new DungeonPlayer(matcher.group("name"), dungeonClass);
    }

    private static String formatClassName(String dungeonClazz) {
        if (dungeonClazz == null || dungeonClazz.isBlank()) {
            return "Unknown";
        }

        String lower = dungeonClazz.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private record DungeonPlayer(String name, String dungeonClass) { }
}

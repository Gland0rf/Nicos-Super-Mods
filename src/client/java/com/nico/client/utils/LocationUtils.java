package com.nico.client.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;

import java.awt.*;
import java.util.Locale;
import java.util.regex.Pattern;

public class LocationUtils {

    public enum Island {
        SINGLE_PLAYER("Singleplayer"),
        PRIVATE_ISLAND("Private Island"),
        GARDEN("Garden"),
        SPIDER_DEN("Spider's Den"),
        CRIMSON_ISLE("Crimson Isle"),
        THE_END("The End"),
        GOLD_MINE("Gold Mine"),
        DEEP_CAVERNS("Deep Caverns"),
        DWARVEN_MINES("Dwarven Mines"),
        CRYSTAL_HOLLOWS("Crystal Hollows"),
        FARMING_ISLAND("The Farming Islands"),
        THE_PARK("The Park"),
        DUNGEON("Catacombs"),
        DUNGEON_HUB("Dungeon Hub"),
        HUB("Hub"),
        DARK_AUCTION("Dark Auction"),
        JERRY_WORKSHOP("Jerry's Workshop"),
        KUUDRA("Kuudra"),
        MINESHAFT("Mineshaft"),
        RIFT("The Rift"),
        BACKWATER_BAYOU("Backwater Bayou"),
        GALATEA("Galatea"),
        UNKNOWN("(Unknown)");

        public final String displayName;

        Island(String displayName) {
            this.displayName = displayName;
        }
    }

    private static boolean inSkyBlock = false;
    private static Island currentArea = Island.UNKNOWN;
    private static String lobbyId = null;

    private static final Pattern LOBBY_REGEX =
            Pattern.compile("\\d\\d/\\d\\d/\\d\\d (\\w{0,6}) *");

    private LocationUtils() { }

    public static boolean isInSkyblock () {
        return inSkyBlock;
    }

    public static Island getCurrentArea () {
        return currentArea;
    }

    public static String getLobbyId() {
        return lobbyId;
    }

    public static boolean isInDungeon() {
        return currentArea == Island.DUNGEON;
    }

    public static boolean isCurrentArea(Island... islands) {
        if (currentArea == Island.SINGLE_PLAYER) {
            return true;
        }

        for (Island island : islands) {
            if (currentArea == island) {
                return true;
            }
        }

        return false;
    }

    public static void onScoreboardObjective(String objectiveName) {
        if ("SBScoreboard".equals(objectiveName)) {
            if (!inSkyBlock) {
                debug("inSkyblock false -> true");
            }

            inSkyBlock = true;
        }
    }

    public static void onTabDisplayName(Component displayName) {
        if (displayName == null) {
            return;
        }

        String text = clean(displayName.getString());

        if (!text.startsWith("Location: ")
                && !text.startsWith("Area: ")
                && !text.startsWith("Dungeon: ")) {
            return;
        }

        debug("tab area line: " + text);

        Island detected = detectIsland(text);

        if (detected != Island.UNKNOWN) {
            setCurrentArea(
                    detected,
                    "tab display name: " + text
            );
        }
    }

    public static void onTeamText(Component prefix, Component suffix) {
        String text = clean(
                (prefix == null ? "" : prefix.getString())
                        + (suffix == null ? "" : suffix.getString())
        );

        var matcher = LOBBY_REGEX.matcher(text);

        if (matcher.find()) {
            lobbyId = matcher.group(1);
            debug("lobbyId=" + lobbyId);
        }
    }

    private static void setCurrentArea(Island area, String reason) {
        if (currentArea != area) {
            debug("area " + currentArea + " -> " + area + " because " + reason);
            currentArea = area;
        }
    }

    private static Island detectIsland(String text) {
        String lower = text.toLowerCase(Locale.ROOT);

        for (Island island : Island.values()) {
            if (island == Island.UNKNOWN) {
                continue;
            }

            if (lower.contains(island.displayName.toLowerCase(Locale.ROOT))) {
                return island;
            }
        }

        return Island.UNKNOWN;
    }

    public static String clean(String text) {
        if (text == null) {
            return "";
        }

        return text
                .replaceAll("§.", "")
                .replaceAll("[^\\x20-\\x7E]", "")
                .trim();
    }

    private static void debug(String message) {
        //System.out.println("[NSM Location] " + message);
    }

}

package com.nico.client.lag;

import com.nico.client.history.SkyblockHistoryFeature;
import com.nico.client.utils.LocationUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.util.Locale;

public final class DungeonRunPacketDetector {
    private DungeonRunPacketDetector() {
    }

    public static void handle(Packet<?> packet) {
        Minecraft minecraft = Minecraft.getInstance();
        LagMonitorService service = LagMonitorService.getInstance();

        Component component = extractText(packet);
        if (component == null) {
            return;
        }

        if (packet instanceof ClientboundSetTitleTextPacket) {
            SkyblockHistoryFeature.onTitle(component);
        }

        String rawText = component.getString();
        String text = normalize(rawText);
        if (text.isEmpty()) {
            return;
        }

        boolean startMarker = packet instanceof ClientboundSystemChatPacket && isDungeonStart(text);
        boolean mortDungeonStart = packet instanceof ClientboundSystemChatPacket && isMortDungeonStart(text);
        boolean endMarker = isDungeonEnd(text);

        if (startMarker) {
            if (LocationUtils.isInSkyblock()) {
                service.onDungeonRunStart();
                SkyblockHistoryFeature.onDungeonRunStart();
            }
            return;
        }

        // Lunar can replace the client world during the dungeon transfer. The memory
        // cleanup resets LocationUtils before the SkyBlock scoreboard is rebuilt, so
        // the normal countdown can be rejected for a few ticks. Mort's map line is
        // dungeon-specific, so it is a safe fallback start signal on Hypixel.
        if (mortDungeonStart) {
            if (!service.isDungeonRunActive()) {
                service.onDungeonRunStart();
                SkyblockHistoryFeature.onDungeonRunStart();
            }
            return;
        }

        if (!LocationUtils.isInDungeon() && !service.isDungeonRunActive()) {
            return;
        }

        if (endMarker) {
            service.onDungeonRunEnd(minecraft);
            boolean failed = text.equals("defeat") || text.equals("defeat!");
            SkyblockHistoryFeature.onDungeonRunEnd(failed);
        }
    }

    private static Component extractText(Packet<?> packet) {
        if (packet instanceof ClientboundSystemChatPacket chatPacket) {
            return chatPacket.content();
        }
        if (packet instanceof ClientboundSetTitleTextPacket titlePacket) {
            return titlePacket.text();
        }
        if (packet instanceof ClientboundSetSubtitleTextPacket subtitlePacket) {
            return subtitlePacket.text();
        }
        if (packet instanceof ClientboundSetActionBarTextPacket actionBarPacket) {
            return actionBarPacket.text();
        }
        return null;
    }

    private static boolean isDungeonStart(String text) {
        return text.contains("starting in 1 second")
                || text.contains("starts in one second");
    }

    private static boolean isMortDungeonStart(String text) {
        return text.contains("mort: here, i found this map when i first entered the dungeon");
    }

    private static boolean isDungeonEnd(String text) {
        return text.equals("victory")
                || text.equals("victory!")
                || text.contains("dungeon cleared")
                || text.contains("extra stats")
                || text.contains("team score:")
                || text.equals("defeat")
                || text.equals("defeat!");
    }

    private static String normalize(String text) {
        return text == null
                ? ""
                : text.toLowerCase(Locale.ROOT)
                .replaceAll("\\u00a7[0-9a-fk-or]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}

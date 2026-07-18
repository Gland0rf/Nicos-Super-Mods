package com.nico.client.lag;

import net.minecraft.client.Minecraft;
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
        LagMonitorService service = LagMonitorService.getInstance();
        Component component = extractText(packet);
        if (component == null) {
            return;
        }

        String rawText = component.getString();
        String text = normalize(rawText);
        if (text.isEmpty()) {
            return;
        }

        if (service.config().debugLogging) {
            System.out.println(
                    "[NSM Lag][Detector] " + packet.getClass().getSimpleName()
                            + " active=" + service.isDungeonRunActive()
                            + " raw=\"" + rawText.replace("\n", "\\n") + "\""
                            + " normalized=\"" + text + "\""
            );
        }

        // The start signal must come from chat, not an action bar/title.
        if (packet instanceof ClientboundSystemChatPacket && isDungeonStart(text)) {
            System.out.println("[NSM Lag][Detector] START matched: " + text);
            service.onDungeonRunStart();
            return;
        }

        if (isDungeonEnd(text)) {
            System.out.println("[NSM Lag][Detector] END matched: " + text);
            service.onDungeonRunEnd(Minecraft.getInstance());
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
        boolean finalCountdown = text.contains("starting in 1 second")
                || text.contains("starts in one second");

        return finalCountdown;
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
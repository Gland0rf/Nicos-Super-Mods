package com.nico.client.lag;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Locale;

public class HypixelServerDetector {
    private HypixelServerDetector() { }

    static boolean isHypixel(Minecraft client) {
        ServerData server = client.getCurrentServer();
        if (server == null || server.ip == null) return false;

        String address = server.ip.toLowerCase(Locale.ROOT).trim();
        int colon = address.indexOf(':');
        if (colon >= 0) {
            address = address.substring(0, colon);
        }

        return address.equals("hypixel.net")
                || address.endsWith(".hypixel.net");
    }
}

package com.nico.client.lag;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public class LagMonitorFeature {
    private static boolean initialized;
    private static LagMonitorConfig config;

    private LagMonitorFeature() { }

    public static synchronized void initialize() {
        if (initialized) return;

        config = LagMonitorConfig.load();
        LagMonitorService.getInstance().configure(config);

        ClientTickEvents.END_CLIENT_TICK.register(
                client -> LagMonitorService.getInstance().tick(client)
        );

        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> LagMonitorService.getInstance().onJoin(client)
        );

        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> LagMonitorService.getInstance().onDisconnect()
        );

        LagMonitorHud.register();
        initialized = true;
        System.out.println("[NSM Lag] Lag monitor initialized");
    }

    public static LagMonitorConfig config() {
        return config;
    }

    public static void saveConfig() {
        if (config != null) {
            config.save();
        }
    }

    public static void openLastSummary(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        client.setScreen(new LagSummaryScreen(
                parent,
                LagMonitorService.getInstance().lastSummary()
        ));
    }
}

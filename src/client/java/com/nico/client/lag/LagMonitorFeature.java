package com.nico.client.lag;

import com.nico.client.configuration.NsmConfig;
import com.nico.client.configuration.category.FeatureOptions;
import com.nico.client.hud.HudLayoutManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public class LagMonitorFeature {
    private static boolean initialized;

    private static final LagMonitorConfig config = new LagMonitorConfig();

    private LagMonitorFeature() { }

    public static synchronized void initialize(HudLayoutManager hudLayout) {
        if (initialized) return;

        syncConfig();

        LagMonitorService service = LagMonitorService.getInstance();
        service.configure(config);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            syncConfig();
            service.tick(client);
        });

        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> service.onJoin(client)
        );

        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> service.onDisconnect()
        );

        LagMonitorHud.register(hudLayout);

        initialized = true;
        System.out.println("[NSM Lag] Lag monitor initialized");
    }

    private static void syncConfig() {
        FeatureOptions.LagMonitor settings = NsmConfig.INSTANCE.feature(FeatureOptions.LagMonitor.class);

        config.applyMoulConfig(settings);
        config.sanitize();
    }

    public static LagMonitorConfig config() {
        return config;
    }

    public static void openLastSummary(Screen parent) {
        Minecraft client = Minecraft.getInstance();
        client.gui.setScreen(new LagSummaryScreen(
                parent,
                LagMonitorService.getInstance().lastSummary()
        ));
    }
}

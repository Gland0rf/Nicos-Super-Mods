package com.nico.client.lag;

import com.nico.client.configuration.NsmConfig;
import com.nico.client.configuration.category.CategoryDungeons;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.function.Supplier;

public class LagMonitorFeature {
    private static boolean initialized;

    private static final LagMonitorConfig config = new LagMonitorConfig();
    private static Supplier<CategoryDungeons.DungeonLagMonitor> settingsSupplier;

    private LagMonitorFeature() { }

    public static synchronized void initialize(Supplier<CategoryDungeons.DungeonLagMonitor> supplier) {
        if (initialized) return;

        settingsSupplier = supplier;
        syncConfig();

        LagMonitorService service = LagMonitorService.getInstance();
        service.configure(config);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Allows MoulConfig changes to take effect immediately.
            syncConfig();
            service.tick(client);
        });

        ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> service.onJoin(client)
        );

        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> service.onDisconnect()
        );

        LagMonitorHud.register();

        initialized = true;
        System.out.println("[NSM Lag] Lag monitor initialized");
    }

    private static void syncConfig() {
        if (settingsSupplier == null) {
            return;
        }

        CategoryDungeons.DungeonLagMonitor settings =
                settingsSupplier.get();

        config.applyMoulConfig(settings);
        config.sanitize();
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

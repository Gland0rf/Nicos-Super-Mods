package com.nico.client;

import com.nico.client.bloodrush.BloodRoutes;
import com.nico.client.bloodrush.RouteContext;
import com.nico.client.bloodrush.RouteEditor;
import com.nico.client.configuration.NsmConfigManager;
import com.nico.client.dungeon.DungeonState;
import com.nico.client.dungeon.DungeonStatsTracker;
import com.nico.client.dungeon.DungeonTeammateScanner;
import com.nico.client.hud.HudLayoutManager;
import com.nico.client.inventoryLayouts.core.InventoryLayoutsFeature;
import com.nico.client.lag.LagMonitorFeature;
import com.nico.client.memleak.MemLeakFeature;
import com.nico.client.stacking.SecretStackingDetector;
import com.nico.client.utils.BazaarService;
import com.nico.client.utils.HypixelApiClient;
//import com.nico.client.wiki.service.HypixelWikiService; TEMPORARY
import com.nico.client.utils.LocationUtils;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.core.BlockPos;

public final class Main implements ClientModInitializer {

    public static Main INSTANCE;

    public static HudLayoutManager HUD_LAYOUT;

    private HypixelApiClient apiClient;
    private BazaarService bazaarService;

    @Override
    public void onInitializeClient() {
        INSTANCE = this;

        System.out.println("[NSM] Client initializer loaded");

        apiClient = new HypixelApiClient(null);
        bazaarService = new BazaarService(apiClient);

        //HypixelWikiService.setBazaarService(bazaarService); TEMPORARY

        MemLeakFeature.initialize();

        HUD_LAYOUT = ClientFeatureInitializer.initialize();
        InventoryLayoutsFeature.initialize();

        NsmClientCommands.register();
        ClientTickHandler.register();

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LocationUtils.reset();
            DungeonState.reset();
            DungeonStatsTracker.reset();
            DungeonTeammateScanner.reset();
        });

        LagMonitorFeature.initialize(() -> NsmConfigManager.getConfig().dungeons.dungeonLagMonitor);
    }

    public HypixelApiClient getApiClient() {
        return apiClient;
    }

    public BazaarService getBazaarService() {
        return bazaarService;
    }
}
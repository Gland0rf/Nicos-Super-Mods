package com.nico.client;

import com.nico.client.bloodrush.BloodRoutes;
import com.nico.client.bloodrush.RouteContext;
import com.nico.client.bloodrush.RouteEditor;
import com.nico.client.configuration.NsmConfigManager;
import com.nico.client.hud.HudLayoutManager;
import com.nico.client.inventoryLayouts.core.InventoryLayoutsFeature;
import com.nico.client.lag.LagMonitorFeature;
import com.nico.client.stacking.SecretStackingDetector;
import com.nico.client.utils.BazaarService;
import com.nico.client.utils.HypixelApiClient;
//import com.nico.client.wiki.service.HypixelWikiService; TEMPORARY
import net.fabricmc.api.ClientModInitializer;
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

        HUD_LAYOUT = ClientFeatureInitializer.initialize();
        InventoryLayoutsFeature.initialize();

        NsmClientCommands.register();
        ClientTickHandler.register();

        HeartRateFeature.initialize(HUD_LAYOUT);

        LagMonitorFeature.initialize(() -> NsmConfigManager.getConfig().dungeons.dungeonLagMonitor);
    }

    public HypixelApiClient getApiClient() {
        return apiClient;
    }

    public BazaarService getBazaarService() {
        return bazaarService;
    }
}
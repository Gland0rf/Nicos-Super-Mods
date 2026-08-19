package com.nico.client;

import com.nico.client.bloodrush.BloodRoutes;
import com.nico.client.bloodrush.RouteContext;
import com.nico.client.bloodrush.RouteEditor;
import com.nico.client.configuration.NsmConfigManager;
import com.nico.client.hud.HudLayoutManager;
import com.nico.client.init.ClientFeatureInitializer;
import com.nico.client.init.ClientTickHandler;
import com.nico.client.init.NsmClientCommands;
import com.nico.client.inventoryLayouts.core.InventoryLayoutsFeature;
import com.nico.client.lag.LagMonitorFeature;
import com.nico.client.modcheck.ModCheckRuntime;
import com.nico.client.modcheck.client.ModCheckTitleButton;
import com.nico.client.modcheck.client.ModCheckWarningScreen;
import com.nico.client.modcheck.scan.ScanReport;
import com.nico.client.stacking.SecretStackingDetector;
import com.nico.client.utils.BazaarService;
import com.nico.client.utils.HypixelApiClient;
//import com.nico.client.wiki.service.HypixelWikiService; TEMPORARY
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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

        // ModCheck
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ScanReport report = ModCheckRuntime.report();
            if (report == null || !report.shouldShowWarning() || ModCheckRuntime.acknowledged()) {
                return;
            }

            if (!report.hasActionableFindings()) {
                return;
            }

            if (client.screen instanceof ModCheckWarningScreen) {
                return;
            }

            client.setScreen(new ModCheckWarningScreen(client.screen, report));
        });

        ModCheckTitleButton.register();

        // API and Bazaar
        apiClient = new HypixelApiClient(null);
        bazaarService = new BazaarService(apiClient);

        //HypixelWikiService.setBazaarService(bazaarService); TEMPORARY

        HUD_LAYOUT = ClientFeatureInitializer.initialize();
        InventoryLayoutsFeature.initialize();

        NsmClientCommands.register();
        ClientTickHandler.register();

        LagMonitorFeature.initialize(() -> NsmConfigManager.getConfig().dungeons.dungeonLagMonitor);
    }

    public HypixelApiClient getApiClient() {
        return apiClient;
    }

    public BazaarService getBazaarService() {
        return bazaarService;
    }
}
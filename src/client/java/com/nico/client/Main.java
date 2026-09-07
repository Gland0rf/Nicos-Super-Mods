package com.nico.client;

import com.nico.client.hud.HudLayoutManager;
import com.nico.client.inventoryLayouts.core.InventoryLayoutsFeature;
import com.nico.client.lag.LagMonitorFeature;
import com.nico.client.lifecycle.WorldStateCleanup;
import com.nico.client.memleak.MemLeakFeature;
import com.nico.client.utils.BazaarService;
import com.nico.client.utils.HypixelApiClient;
import net.fabricmc.api.ClientModInitializer;

public final class Main implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        System.out.println("[NSM] Client initializer loaded");

        HypixelApiClient apiClient = new HypixelApiClient(null);
        BazaarService bazaarService = new BazaarService(apiClient);

        HudLayoutManager hudLayout = ClientFeatureInitializer.initialize(bazaarService);
        MemLeakFeature.initialize();

        InventoryLayoutsFeature.initialize();

        NsmClientCommands.register();
        ClientTickHandler.register();

        WorldStateCleanup.register();
        LagMonitorFeature.initialize(hudLayout);
    }
}
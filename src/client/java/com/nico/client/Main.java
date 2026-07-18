package com.nico.client;

import com.nico.client.configuration.NsmConfig;
import com.nico.client.configuration.NsmConfigManager;
import com.nico.client.hud.HudLayoutManager;
import com.nico.client.lag.LagMonitorFeature;
import com.nico.client.stacking.SecretStackingDetector;
import com.nico.client.utils.BazaarService;
import com.nico.client.utils.HypixelApiClient;
import com.nico.client.wiki.service.HypixelWikiService;
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

        HypixelWikiService.setBazaarService(bazaarService);

        HUD_LAYOUT = ClientFeatureInitializer.initialize();

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

    /*
     * Compatibility methods.
     *
     * Existing mixins, packet hooks, or Kotlin code can continue calling
     * SecretStackTrackerClient without knowing about the new detector class.
     */

    public static void onRoomSecretsPacket(int foundSecrets, int totalSecrets) {
        SecretStackingDetector.onRoomSecretsPacket(foundSecrets, totalSecrets);
    }

    public static void onRoomChanged() {
        SecretStackingDetector.onRoomChanged();
    }

    public static void onOdinSecretPickup(BlockPos secretPos) {
        SecretStackingDetector.onOdinSecretPickup(secretPos);
    }

    public static void onRoomSecretCounterUpdate(
            String roomName,
            int newSecretCount
    ) {
        SecretStackingDetector.onRoomSecretCounterUpdate(
                roomName,
                newSecretCount
        );
    }
}
package com.nico.client;

import com.nico.client.configuration.NsmConfigManager;
import com.nico.client.hud.HudLayoutManager;
import com.nico.client.hud.HudMoveCommand;
import com.nico.client.minions.MinionRoiClient;
import com.nico.client.minions.base.MinionDataRegistry;
import com.nico.client.secretTimer.SecretPacketHooks;
import com.nico.client.secretTimer.SecretRoomTimerClient;
import com.nico.client.utils.BazaarService;
import com.nico.client.utils.SecretEventBridge;

import java.io.IOException;

public final class ClientFeatureInitializer {

    private ClientFeatureInitializer() {
    }

    public static HudLayoutManager initialize() {
        initializeConfig();
        initializeSecretFeatures();

        HudLayoutManager hudLayout = initializeHud();

        initializeMinionFeatures(hudLayout);

        return hudLayout;
    }

    private static void initializeConfig() {
        NsmConfigManager.init();
    }

    private static void initializeSecretFeatures() {
        SecretPacketHooks.init();
        SecretEventBridge.INSTANCE.init();
        SecretRoomTimerClient.init();
    }

    private static HudLayoutManager initializeHud() {
        try {
            HudLayoutManager hudLayout = new HudLayoutManager();
            HudMoveCommand.register(hudLayout);
            return hudLayout;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to initialize the NSM HUD",
                    exception
            );
        }
    }

    private static void initializeMinionFeatures(
            HudLayoutManager hudLayout
    ) {
        try {
            MinionDataRegistry registry =
                    MinionDataRegistry.loadFromResources(
                            "/assets/nsm/minions.json"
                    );

            BazaarService bazaarService = Main.INSTANCE.getBazaarService();

            MinionRoiClient.init(
                    registry,
                    bazaarService,
                    hudLayout
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Failed to load minion data",
                    exception
            );
        }
    }
}
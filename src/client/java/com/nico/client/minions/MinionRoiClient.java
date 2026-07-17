package com.nico.client.minions;

import com.nico.client.hud.HudLayoutManager;
import com.nico.client.minions.base.MinionDataRegistry;
import com.nico.client.utils.BazaarService;
import com.odtheking.odin.events.ScreenEvent;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

public final class MinionRoiClient {
    private static MinionGuiOverlay overlay;

    private MinionRoiClient() {
    }

    public static void init(
            MinionDataRegistry registry,
            BazaarService bazaarService,
            HudLayoutManager layoutManager
    ) {
        overlay = new MinionGuiOverlay(
                registry,
                new MinionOutputEstimator(),
                bazaarService,
                layoutManager
        );

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            ScreenEvents.afterRender(screen).register(
                    (screen1, graphics, mouseX, mouseY, tickProgress) -> {
                        overlay.onRenderPost(screen1, graphics);
                    }
            );
        });
    }
}
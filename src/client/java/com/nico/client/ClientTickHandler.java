package com.nico.client;

import com.nico.client.configuration.NsmConfig;
import com.nico.client.dungeon.DungeonState;
import com.nico.client.stacking.RoomStackingDetector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class ClientTickHandler {

    private static final int TICKS_PER_SECOND = 20;

    private static int tickCounter;

    private ClientTickHandler() {
    }

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> onClientTick());
    }

    private static void onClientTick() {
        tickCounter++;

        DungeonState.tick();

        if (tickCounter % TICKS_PER_SECOND == 0) {
            tickOncePerSecond();
        }
    }

    private static void tickOncePerSecond() {
        if (NsmConfig.INSTANCE.dungeons.roomStacking.enabled) {
            RoomStackingDetector.tick();
        }
    }
}
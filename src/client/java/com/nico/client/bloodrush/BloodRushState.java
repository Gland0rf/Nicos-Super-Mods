package com.nico.client.bloodrush;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

import java.util.function.BooleanSupplier;

public final class BloodRushState {
    private static final String BLOOD_DOOR_OPENED = "The BLOOD DOOR has been opened!";;

    private boolean bloodOpen;
    private boolean wasInDungeon;

    public void register(BooleanSupplier isInDungeon) {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (BLOOD_DOOR_OPENED.equals(message.getString())) {
                bloodOpen = true;
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean nowInDungeon = isInDungeon.getAsBoolean();

            if (nowInDungeon && !wasInDungeon) {
                bloodOpen = false;
            }

            if (!nowInDungeon) {
                bloodOpen = false;
            }

            wasInDungeon = nowInDungeon;
        });
    }

    public boolean isBloodOpen() {
        return bloodOpen;
    }

    public void markBloodOpen() {
        bloodOpen = true;
    }

    public void reset() {
        bloodOpen = false;
    }
}

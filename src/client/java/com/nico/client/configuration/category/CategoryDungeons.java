package com.nico.client.configuration.category;

import com.nico.client.configuration.FeatureId;
import com.nico.client.secretTimer.SecretRoomTimerClient;
import io.github.notenoughupdates.moulconfig.annotations.*;
import net.minecraft.world.scores.Team;

public class CategoryDungeons {
    @FeatureId(value = "roomStacking")
    @ConfigOption(name = "Room Stacking Detector", desc = "Detects when two or more dungeon teammates are in the same room.")
    @Accordion
    public FeatureOptions.RoomStacking roomStacking = new FeatureOptions.RoomStacking();

    @FeatureId(value = "secretRoomTimer")
    @ConfigOption(name = "Room Secret Timer", desc = "Times how long it takes you to personally complete all secrets in a room, alone.")
    @Accordion
    public FeatureOptions.SecretRoomTimer secretRoomTimer = new FeatureOptions.SecretRoomTimer();
}
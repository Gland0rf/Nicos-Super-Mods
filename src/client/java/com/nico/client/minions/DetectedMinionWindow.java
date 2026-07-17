package com.nico.client.minions;

import com.nico.client.minions.base.DetectedMinionModifier;
import com.nico.client.minions.base.MinionData;
import com.nico.client.minions.base.MinionSetup;

import java.util.List;
import java.util.stream.Collectors;

public record DetectedMinionWindow(
        String title,
        String minionId,
        int tier,
        MinionData minionData,
        MinionSetup setup,
        List<DetectedMinionModifier> detectedModifiers
) {
    public String signature() {
        String modifierSignature = detectedModifiers.stream()
                .map(DetectedMinionModifier::getDisplayName)
                .sorted()
                .collect(Collectors.joining(","));

        return minionId + ":" + tier + ":" + modifierSignature;
    }
}
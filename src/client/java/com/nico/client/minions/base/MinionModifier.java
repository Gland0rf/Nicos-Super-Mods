package com.nico.client.minions.base;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MinionModifier {
    private final String name;
    private final double additiveSpeedPercent;
    private final double outputMultiplier;
    private final List<MinionOutputDrop> extraDropsPerCycle;

    private MinionModifier(
            String name,
            double additiveSpeedPercent,
            double outputMultiplier,
            List<MinionOutputDrop> extraDropsPerCycle
    ) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Modifier cannot be null or empty");
        }

        if (outputMultiplier < 0.0D) {
            throw new IllegalArgumentException("Modifier cannot be negative");
        }

        this.name = name;
        this.additiveSpeedPercent = additiveSpeedPercent;
        this.outputMultiplier = outputMultiplier;
        this.extraDropsPerCycle = Collections.unmodifiableList(
                new ArrayList<>(extraDropsPerCycle)
        );
    }

    public static MinionModifier speed(String name, double additiveSpeedPercent) {
        return new MinionModifier(
                name,
                additiveSpeedPercent,
                1.0D,
                Collections.<MinionOutputDrop>emptyList()
        );
    }

    public static MinionModifier outputMultiplier(String name, double outputMultiplier) {
        return new MinionModifier(
                name,
                0.0D,
                outputMultiplier,
                Collections.<MinionOutputDrop>emptyList()
        );
    }

    public static MinionModifier extraDrop(String name, MinionOutputDrop drop) {
        if (drop == null) {
            throw new IllegalArgumentException("drop cannot be null");
        }

        return new MinionModifier(
                name,
                0.0D,
                1.0D,
                Collections.singletonList(drop)
        );
    }

    public static MinionModifier custom(
            String name,
            double additiveSpeedPercent,
            double outputMultiplier,
            List<MinionOutputDrop> extraDropsPerCycle
    ) {
        return new MinionModifier(
                name,
                additiveSpeedPercent,
                outputMultiplier,
                extraDropsPerCycle == null
                        ? Collections.<MinionOutputDrop>emptyList()
                        : extraDropsPerCycle
        );
    }

    public String getName() {
        return name;
    }

    public double getAdditiveSpeedPercent() {
        return additiveSpeedPercent;
    }

    public double getOutputMultiplier() {
        return outputMultiplier;
    }

    public List<MinionOutputDrop> getExtraDropsPerCycle() {
        return extraDropsPerCycle;
    }
}
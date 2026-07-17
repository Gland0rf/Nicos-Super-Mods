package com.nico.client.minions.base;

import java.util.*;

public final class MinionDefinition {
    private final String minionId;
    private final int actionsPerCycle;
    private final Map<Integer, Double> secondsBetweenActionsByTier;
    private final List<MinionOutputDrop> baseDrops;

    public MinionDefinition(
            String minionId,
            int actionsPerCycle,
            Map<Integer, Double> secondsBetweenActionsByTier,
            List<MinionOutputDrop> baseDrops
    ) {
        if (minionId == null || minionId.trim().isEmpty()) {
            throw new IllegalArgumentException("minionId cannot be null or empty");
        }

        if (actionsPerCycle <= 0) {
            throw new IllegalArgumentException("actionsPerCycle must be greater than 0");
        }

        if (secondsBetweenActionsByTier == null || secondsBetweenActionsByTier.isEmpty()) {
            throw new IllegalArgumentException("secondsBetweenActionsByTier cannot be null or empty");
        }

        if (baseDrops == null || baseDrops.isEmpty()) {
            throw new IllegalArgumentException("baseDrops cannot be null or empty");
        }

        this.minionId = minionId;
        this.actionsPerCycle = actionsPerCycle;
        this.secondsBetweenActionsByTier = Collections.unmodifiableMap(
                new LinkedHashMap<>(secondsBetweenActionsByTier)
        );
        this.baseDrops = Collections.unmodifiableList(new ArrayList<>(baseDrops));
    }

    public static MinionDefinition of(
            String minionId,
            int actionsPerCycle,
            Map<Integer, Double> secondsBetweenActionsByTier,
            MinionOutputDrop... baseDrops
    ) {
        return new MinionDefinition(
                minionId,
                actionsPerCycle,
                secondsBetweenActionsByTier,
                Arrays.asList(baseDrops)
        );
    }

    public String getMinionId() {
        return minionId;
    }

    public int getActionsPerCycle() {
        return actionsPerCycle;
    }

    public List<MinionOutputDrop> getBaseDrops() {
        return baseDrops;
    }

    public double getSecondsBetweenActions(int tier) {
        Double seconds = secondsBetweenActionsByTier.get(tier);

        if (seconds == null) {
            throw new IllegalArgumentException(
                    "No action time known for " + minionId + " tier " + tier
            );
        }

        if (seconds <= 0.0D) {
            throw new IllegalArgumentException(
                    "Invalid action time for " + minionId + " tier " + tier
            );
        }

        return seconds;
    }
}
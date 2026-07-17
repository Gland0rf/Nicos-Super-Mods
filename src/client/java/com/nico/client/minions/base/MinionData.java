package com.nico.client.minions.base;

import java.util.Map;

public record MinionData(
        String minionId,
        String displayName,
        String category,
        int maxTier,
        boolean simpleEstimatorEnabled,
        String tierDataQuality,
        MinionDefinition definition,
        Map<Integer, MinionUpgradeRecipe> upgradeRecipesByFromTier
) {
    public MinionUpgradeRecipe getUpgradeRecipeFromTier(int tier) {
        return upgradeRecipesByFromTier.get(tier);
    }
}
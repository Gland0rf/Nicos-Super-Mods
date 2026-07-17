package com.nico.client.minions.base;

import java.util.List;

public record MinionUpgradeRecipe (
        int fromTier,
        int toTier,
        List<MinionUpgradeMaterial> materials
) {

}
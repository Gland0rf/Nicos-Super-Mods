package com.nico.client.minions.base;

import java.util.List;

public record MinionUpgradeEstimate (
        String minionId,
        int fromTier,
        int toTier,
        List<MinionUpgradeMaterial> materials,
        double upgradeCost,
        double currentCoinsPerDay,
        double nextCoinsPerDay,
        double extraCoinsPerDay,
        double paybackDays
) {
    public boolean canPayBack() {
        return extraCoinsPerDay > 0.0D && Double.isFinite(paybackDays);
    }
}
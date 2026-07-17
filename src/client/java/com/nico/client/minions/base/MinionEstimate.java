package com.nico.client.minions.base;

import java.util.*;

public record MinionEstimate(String minionId, int tier, double baseSecondsBetweenActions,
                             double effectiveSecondsBetweenActions, double totalAdditiveSpeedPercent,
                             double outputMultiplier, double cyclesPerDay, Map<String, Double> itemsPerDay,
                             Map<String, Double> coinValueByProduct, List<String> missingBazaarPrices,
                             double coinsPerDay) {
    public MinionEstimate(
            String minionId,
            int tier,
            double baseSecondsBetweenActions,
            double effectiveSecondsBetweenActions,
            double totalAdditiveSpeedPercent,
            double outputMultiplier,
            double cyclesPerDay,
            Map<String, Double> itemsPerDay,
            Map<String, Double> coinValueByProduct,
            List<String> missingBazaarPrices,
            double coinsPerDay
    ) {
        this.minionId = minionId;
        this.tier = tier;
        this.baseSecondsBetweenActions = baseSecondsBetweenActions;
        this.effectiveSecondsBetweenActions = effectiveSecondsBetweenActions;
        this.totalAdditiveSpeedPercent = totalAdditiveSpeedPercent;
        this.outputMultiplier = outputMultiplier;
        this.cyclesPerDay = cyclesPerDay;
        this.itemsPerDay = Collections.unmodifiableMap(new LinkedHashMap<>(itemsPerDay));
        this.coinValueByProduct = Collections.unmodifiableMap(new LinkedHashMap<>(coinValueByProduct));
        this.missingBazaarPrices = Collections.unmodifiableList(new ArrayList<>(missingBazaarPrices));
        this.coinsPerDay = coinsPerDay;
    }
}
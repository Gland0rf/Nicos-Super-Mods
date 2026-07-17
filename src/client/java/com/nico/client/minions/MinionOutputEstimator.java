package com.nico.client.minions;

import com.nico.client.minions.base.*;
import com.nico.client.utils.BazaarService;

import java.io.IOException;
import java.util.*;

public class MinionOutputEstimator {

    private static final double SECONDS_PER_DAY = 86_400.0D;

    public MinionEstimate estimate(MinionSetup setup, BazaarService bazaarService) throws IOException {
        if (setup == null) {
            throw new IllegalArgumentException("Minion setup cannot be null");
        }

        if (bazaarService == null) {
            throw new IllegalArgumentException("bazaarService cannot be null");
        }

        MinionDefinition definition = setup.getDefinition();

        double baseSecondsBetweenActions = definition.getSecondsBetweenActions(setup.getTier());

        double totalAdditiveSpeedPercent = 0.0D;
        double outputMultiplier = 1.0D;

        List<MinionOutputDrop> drops = new ArrayList<>(definition.getBaseDrops());

        for (MinionModifier modifier : setup.getModifiers()) {
            totalAdditiveSpeedPercent += modifier.getAdditiveSpeedPercent();
            outputMultiplier *= modifier.getOutputMultiplier();
            drops.addAll(modifier.getExtraDropsPerCycle());
        }

        double speedMultiplier = 1.0D + totalAdditiveSpeedPercent / 100.0D;

        if (speedMultiplier <= 0.0D) {
            throw new IllegalArgumentException(
                    "Total speed multiplier must be positive. Got " + speedMultiplier
            );
        }

        double effectiveSecondsBetweenActions = baseSecondsBetweenActions / speedMultiplier;

        double cyclesPerDay = SECONDS_PER_DAY /
                (effectiveSecondsBetweenActions * definition.getActionsPerCycle());

        Map<String, Double> itemsPerDay = new LinkedHashMap<>();

        for (MinionOutputDrop drop : drops) {
            double multiplier = drop.isAffectedByOutputMultiplier() ? outputMultiplier : 1.0D;
            double amountPerDay = cyclesPerDay * drop.getAmountPerCycle() * multiplier;

            add(itemsPerDay, drop.getProductId(), amountPerDay);
        }

        Map<String, Double> coinValueByProduct = new LinkedHashMap<>();
        List<String> missingBazaarPrices = new ArrayList<>();

        double coinsPerDay = 0.0D;

        for (Map.Entry<String, Double> entry : itemsPerDay.entrySet()) {
            String productId = entry.getKey();
            double amount = entry.getValue();

            OptionalDouble price = bazaarService.getPrice(productId, setup.getPriceMode());

            if (!price.isPresent()) {
                missingBazaarPrices.add(productId);
                continue;
            }

            double value = amount * price.getAsDouble();
            coinValueByProduct.put(productId, value);
            coinsPerDay += value;
        }

        return new MinionEstimate(
                definition.getMinionId(),
                setup.getTier(),
                baseSecondsBetweenActions,
                effectiveSecondsBetweenActions,
                totalAdditiveSpeedPercent,
                outputMultiplier,
                cyclesPerDay,
                itemsPerDay,
                coinValueByProduct,
                missingBazaarPrices,
                coinsPerDay
        );
    }

    private static void add(Map<String, Double> map, String productId, double amount) {
        Double previous = map.get(productId);

        if (previous == null) {
            map.put(productId, amount);
        }else {
            map.put(productId, previous + amount);
        }
    }
}

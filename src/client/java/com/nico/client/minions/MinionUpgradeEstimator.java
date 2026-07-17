package com.nico.client.minions;

import com.nico.client.configuration.NsmConfig;
import com.nico.client.minions.base.*;
import com.nico.client.utils.BazaarService;

import java.io.IOException;
import java.util.Optional;
import java.util.OptionalDouble;

public class MinionUpgradeEstimator {
    private final MinionOutputEstimator outputEstimator;

    public MinionUpgradeEstimator(MinionOutputEstimator outputEstimator) {
        this.outputEstimator = outputEstimator;
    }

    public Optional<MinionUpgradeEstimate> estimateNextUpgrade(
            MinionData minionData,
            MinionSetup currentSetup,
            BazaarService bazaarService
    ) throws IOException {
        if (minionData == null || currentSetup == null || bazaarService == null) {
            return Optional.empty();
        }

        int currentTier = currentSetup.getTier();

        if (currentTier >= minionData.maxTier()) {
            return Optional.empty();
        }

        MinionUpgradeRecipe recipe = minionData.getUpgradeRecipeFromTier(currentTier);

        if (recipe == null) {
            return Optional.empty();
        }

        double upgradeCost = calculateUpgradeCost(recipe, bazaarService);

        MinionEstimate currentEstimate =
                outputEstimator.estimate(currentSetup, bazaarService);

        MinionSetup nextSetup = copySetupForTier(currentSetup, recipe.toTier());

        MinionEstimate nextEstimate =
                outputEstimator.estimate(nextSetup, bazaarService);

        double currentCoinsPerDay = currentEstimate.coinsPerDay();
        double nextCoinsPerDay = nextEstimate.coinsPerDay();
        double extraCoinsPerDay = nextCoinsPerDay - currentCoinsPerDay;

        double paybackCoins = extraCoinsPerDay > 0.0D
                ? upgradeCost / extraCoinsPerDay
                : Double.POSITIVE_INFINITY;

        return Optional.of(new MinionUpgradeEstimate(
                minionData.minionId(),
                recipe.fromTier(),
                recipe.toTier(),
                recipe.materials(),
                upgradeCost,
                currentCoinsPerDay,
                nextCoinsPerDay,
                extraCoinsPerDay,
                paybackCoins
        ));
    }

    private double calculateUpgradeCost(
            MinionUpgradeRecipe recipe,
            BazaarService bazaarService
    ) throws IOException {
        double total = 0.0D;

        for (MinionUpgradeMaterial material : recipe.materials()) {
            if (material.productId() == null || material.productId().isEmpty()) {
                continue;
            }

            BazaarService.PriceMode priceMode =
                    NsmConfig.INSTANCE.island.minionInfo.bazaarPriceMode == 1
                            ? BazaarService.PriceMode.INSTANT_BUY
                            : BazaarService.PriceMode.INSTANT_SELL;

            OptionalDouble price =
                    bazaarService.getPrice(material.productId(), priceMode);

            if (!price.isPresent()) {
                continue;
            }

            total += price.getAsDouble() * material.amount();
        }

        return total;
    }

    private MinionSetup copySetupForTier(MinionSetup currentSetup, int tier) {
        MinionSetup nextSetup = new MinionSetup(
                currentSetup.getDefinition(),
                tier
        ).setPriceMode(currentSetup.getPriceMode());

        for (MinionModifier modifier : currentSetup.getModifiers()) {
            nextSetup.addModifier(modifier);
        }

        return nextSetup;
    }
}

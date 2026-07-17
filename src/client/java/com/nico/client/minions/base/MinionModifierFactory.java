package com.nico.client.minions.base;

import java.util.Locale;
import java.util.Optional;

public final class MinionModifierFactory {
    private MinionModifierFactory() {
    }

    public static Optional<DetectedMinionModifier> fromItemName(
            String rawDisplayName,
            MinionDefinition definition
    ) {
        if (rawDisplayName == null || rawDisplayName.trim().isEmpty()) {
            return Optional.empty();
        }

        String name = cleanName(rawDisplayName);

        switch (name) {
            case "Coal":
            case "Block of Coal":
            case "Enchanted Bread":
                return Optional.of(speed(name, 5.0D));

            case "Enchanted Coal":
                return Optional.of(speed(name, 10.0D));

            case "Enchanted Charcoal":
                return Optional.of(speed(name, 20.0D));

            case "Solar Panel":
                return Optional.of(DetectedMinionModifier.unsupported(
                        name,
                        "Solar Panel is conditional: island loaded + daytime/Day Saver"
                ));

            case "Enchanted Lava Bucket":
                return Optional.of(speed(name, 25.0D));

            case "Magma Bucket":
                return Optional.of(speed(name, 30.0D));

            case "Plasma Bucket":
                return Optional.of(speed(name, 35.0D));

            case "Everburning Flame":
                return Optional.of(speed(name, 40.0D));

            case "Hamster Wheel":
                return Optional.of(speed(name, 50.0D));

            case "Foul Flesh":
                return Optional.of(speed(name, 90.0D));

            case "Tasty Cheese":
                return Optional.of(outputMultiplier(name, 2.0D));

            case "Catalyst":
                return Optional.of(outputMultiplier(name, 3.0D));

            case "Hyper Catalyst":
                return Optional.of(outputMultiplier(name, 4.0D));

            case "Minion Expander":
                return Optional.of(speed(name, 5.0D));

            case "Flycatcher":
            case "Fly Catcher":
                return Optional.of(speed("Flycatcher", 20.0D));

            case "Diamond Spreading":
                return Optional.of(diamondSpreading(definition));

            case "Mithril Infusion":
                return Optional.of(speed(name, 10.0D));

            case "Free Will":
                return Optional.of(speed(name, 10.0D));

            case "Postcard":
                return Optional.of(speed(name, 5.0D));

            case "Compactor":
            case "Super Compactor 3000":
            case "Dwarven Super Compactor":
            case "Auto Smelter":
                return Optional.of(DetectedMinionModifier.unsupported(
                        name,
                        "Output transformation is not modeled yet"
                ));

            case "Budget Hopper":
            case "Enchanted Hopper":
                return Optional.of(DetectedMinionModifier.unsupported(
                        name,
                        "Automated shipping percentage is not modeled yet"
                ));

            case "Corrupt Soil":
            case "Lesser Soulflow Engine":
            case "Soulflow Engine":
            case "Berberis Fuel Injector":
            case "Sleepy Hollow":
            case "Krampus Helmet":
            case "Potato Spreading":
            case "Enchanted Egg":
            case "Flint Shovel":
            case "Enchanted Shears":
            case "Thorny Vines":
            case "Dayswitch":
            case "Nightswitch":
                return Optional.of(DetectedMinionModifier.unsupported(
                        name,
                        "Special output rule is not modeled yet"
                ));

            default:
                return Optional.empty();
        }
    }

    private static DetectedMinionModifier speed(String name, double additiveSpeedPercent) {
        return DetectedMinionModifier.modeled(
                name,
                MinionModifier.speed(name, additiveSpeedPercent)
        );
    }

    private static DetectedMinionModifier outputMultiplier(String name, double multiplier) {
        return DetectedMinionModifier.modeled(
                name,
                MinionModifier.outputMultiplier(name, multiplier)
        );
    }

    private static DetectedMinionModifier diamondSpreading(MinionDefinition definition) {
        double baseItemsPerCycle = 1.0D;

        if (definition != null && definition.getBaseDrops() != null && !definition.getBaseDrops().isEmpty()) {
            baseItemsPerCycle = definition.getBaseDrops()
                    .stream()
                    .mapToDouble(MinionOutputDrop::getAmountPerCycle)
                    .sum();
        }

        double diamondsPerCycle = baseItemsPerCycle * 0.10D;

        return DetectedMinionModifier.modeled(
                "Diamond Spreading",
                MinionModifier.extraDrop(
                        "Diamond Spreading",
                        MinionOutputDrop.extra("DIAMOND", diamondsPerCycle)
                )
        );
    }

    private static String cleanName(String value) {
        return value
                .replaceAll("§.", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String normalizeForSignature(String value) {
        return cleanName(value)
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
    }
}
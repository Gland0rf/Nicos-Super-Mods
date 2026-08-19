package com.nico.client.utils.tradeprot.valuation.records;

import java.util.Locale;
import java.util.Optional;

public enum GemstoneType {
    RUBY,
    AMETHYST,
    JADE,
    SAPPHIRE,
    AMBER,
    TOPAZ,
    JASPER,
    OPAL,
    ONYX,
    AQUAMARINE,
    CITRINE,
    PERIDOT;

    public static Optional<GemstoneType> fromToken(String value) {
        if (value == null || value.isBlank()) return  Optional.empty();
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
        for (GemstoneType type : values()) {
            if (normalized.equals(type.name())
                    || normalized.startsWith(type.name() + "_")
                    || normalized.endsWith("_" + type.name())) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}

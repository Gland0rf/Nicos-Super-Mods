package com.nico.client.utils.tradeprot.valuation.records;

import java.util.Locale;

public record AppliedGemstone (
        String slot,
        GemstoneType type,
        String quality
) {
    public AppliedGemstone {
        slot = normalize(slot);
        quality = normalize(quality);
    }

    public boolean valid() {
        return !slot.isBlank() && type != null && isKnownQuality(quality);
    }

    public String bazaarProductId() {
        return valid() ? quality + "_" + type.name() + "_GEM" : "";
    }

    private static boolean isKnownQuality(String value) {
        return switch (value) {
            case "ROUGH", "FLAWED", "FINE", "FLAWLESS", "PERFECT" -> true;
            default -> false;
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }
}

package com.nico.client.utils.tradeprot.valuation.records;

import java.util.List;
import java.util.Map;

public record SkyblockItemData(
        String internalId,
        String displayName,
        int count,
        String modifier,
        int rarityUpgrades,
        int hotPotatoCount,
        int upgradeLevel,
        int artOfWarCount,
        int farmingForDummiesCount,
        int transmissionTunerCount,
        boolean woodSingularityApplied,
        String powerScroll,
        Map<String, Integer> enchantments,
        List<AppliedGemstone> gemstones,
        List<String> abilityScrolls,
        Map<String, String> rawExtraAttributes
) {
    public SkyblockItemData {
        internalId = safe(internalId);
        displayName = safe(displayName);
        count = Math.max(1, count);
        modifier = safe(modifier);
        rarityUpgrades = Math.max(0, rarityUpgrades);
        hotPotatoCount = Math.max(0, hotPotatoCount);
        upgradeLevel = Math.max(0, upgradeLevel);
        artOfWarCount = Math.max(0, artOfWarCount);
        farmingForDummiesCount = Math.max(0, farmingForDummiesCount);
        transmissionTunerCount = Math.max(0, transmissionTunerCount);
        powerScroll = safe(powerScroll);
        enchantments = enchantments == null ? Map.of() : Map.copyOf(enchantments);
        gemstones = gemstones == null ? List.of() : List.copyOf(gemstones);
        abilityScrolls = abilityScrolls == null ? List.of() : List.copyOf(abilityScrolls);
        rawExtraAttributes = rawExtraAttributes == null ? Map.of() : Map.copyOf(rawExtraAttributes);
    }

    public boolean valid() { return !internalId.isBlank(); }
    public int normalStars() { return Math.min(5, upgradeLevel); }
    public int masterStars() { return Math.max(0, Math.min(5, upgradeLevel - 5)); }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

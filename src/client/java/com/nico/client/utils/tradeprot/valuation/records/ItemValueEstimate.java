package com.nico.client.utils.tradeprot.valuation.records;

import java.util.List;

public record ItemValueEstimate (
        SkyblockItemData item,
        long estimatedCoins,
        Confidence confidence,
        List<ValueComponent> components,
        List<String> unknowns
) {
    public ItemValueEstimate {
        estimatedCoins = Math.max(0L, estimatedCoins);
        confidence = confidence == null ? Confidence.NONE : confidence;
        components = components == null ? List.of() : List.copyOf(components);
        unknowns = unknowns == null ? List.of() : List.copyOf(unknowns);
    }

    public boolean complete() { return confidence != Confidence.NONE && unknowns.isEmpty(); }

    public enum Confidence { NONE, LOW, MEDIUM, HIGH }
}
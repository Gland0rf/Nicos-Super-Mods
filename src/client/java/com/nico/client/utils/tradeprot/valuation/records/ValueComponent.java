package com.nico.client.utils.tradeprot.valuation.records;

public record ValueComponent(
        String key,
        String label,
        long coins,
        int quantity,
        boolean includedInTotal,
        String source
) {
    public ValueComponent {
        key = key == null ? "" : key.trim();
        label = label == null ? "" : label.trim();
        coins = Math.max(0L, coins);
        quantity = Math.max(1, quantity);
        source = source == null ?  "" : source.trim();
    }
}

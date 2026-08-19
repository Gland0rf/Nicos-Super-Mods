package com.nico.client.utils.tradeprot.valuation.records;

import java.util.List;

public record TradeValueResult (
        long yourCoins,
        long theirCoins,
        long yourTotal,
        long theirTotal,
        long differenceCoins,
        double differencePercent,
        Verdict verdict,
        List<ItemValueEstimate> yourItems,
        List<ItemValueEstimate> theirItems
) {
    public TradeValueResult {
        yourCoins = Math.max(0L, yourCoins);
        theirCoins = Math.max(0L, theirCoins);
        yourTotal = Math.max(0L, yourTotal);
        theirTotal = Math.max(0L, theirTotal);
        verdict = verdict == null ? Verdict.INCOMPLETE : verdict;
        yourItems = yourItems == null ? List.of() : List.copyOf(yourItems);
        theirItems = theirItems == null ? List.of() : List.copyOf(theirItems);
    }

    public enum Verdict { INCOMPLETE, FAIR, SLIGHT_UNDERPAY, UNDERPAY, MAJOR_UNDERPAY, OVERPAY, MAJOR_OVERPAY }
}
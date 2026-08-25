package com.nico.client.utils.tradeprot.valuation;

import com.nico.client.utils.tradeprot.valuation.records.ItemValueEstimate;
import com.nico.client.utils.tradeprot.valuation.records.TradeValueResult;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class TradeValueService {

    private final ItemValueService itemValueService;

    public TradeValueService(ItemValueService itemValueService) {
        if (itemValueService == null) throw new IllegalArgumentException("itemValueService cannot be null");
        this.itemValueService = itemValueService;
    }

    public CompletableFuture<TradeValueResult> evaluate(List<ItemStack> yourItems, long yourCoins, List<ItemStack> theirItems, long theirCoins) {
        CompletableFuture<List<ItemValueEstimate>> yours = estimateAll(yourItems);
        CompletableFuture<List<ItemValueEstimate>> theirs = estimateAll(theirItems);

        return yours.thenCombine(theirs, (yourValues, theirValues) -> {
            long yourTotal = saturatingAdd(Math.max(0L, yourCoins), sum(yourValues));
            long theirTotal = saturatingAdd(Math.max(0L, theirCoins), sum(theirValues));
            long difference = saturatingSubtract(theirTotal, yourTotal);
            double percent = yourTotal <= 0L ? 0.0D : ((double) difference / (double) yourTotal) * 100.0D;
            boolean incomplete = hasUnsafeValue(yourValues) || hasUnsafeValue(theirValues);

            return new TradeValueResult(yourCoins, theirCoins, yourTotal, theirTotal, difference, percent,
                    incomplete ? TradeValueResult.Verdict.INCOMPLETE : verdict(percent), yourValues, theirValues);
        });
    }

    private CompletableFuture<List<ItemValueEstimate>> estimateAll(List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) return CompletableFuture.completedFuture(List.of());
        List<CompletableFuture<ItemValueEstimate>> futures = new ArrayList<>();
        for (ItemStack stack : stacks) if (stack != null && !stack.isEmpty()) futures.add(itemValueService.estimate(stack));
        if (futures.isEmpty()) return CompletableFuture.completedFuture(List.of());
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    private static long sum(List<ItemValueEstimate> values) {
        long result = 0;
        for (ItemValueEstimate value : values) result = saturatingAdd(result, value.estimatedCoins());
        return result;
    }

    private static boolean hasUnsafeValue(List<ItemValueEstimate> values) {
        for (ItemValueEstimate value : values) if (value.confidence() == ItemValueEstimate.Confidence.NONE) return true;
        return false;
    }

    private static TradeValueResult.Verdict verdict(double percent) {
        if (percent <= -20.0D) return TradeValueResult.Verdict.MAJOR_UNDERPAY;
        if (percent <= -10.0D) return TradeValueResult.Verdict.UNDERPAY;
        if (percent <= -5.0D) return TradeValueResult.Verdict.SLIGHT_UNDERPAY;
        if (percent >= 20.0D) return TradeValueResult.Verdict.MAJOR_OVERPAY;
        if (percent >= 5.0D) return TradeValueResult.Verdict.OVERPAY;
        return TradeValueResult.Verdict.FAIR;
    }

    private static long saturatingAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left - right;
    }

    private static long saturatingSubtract(long left, long right) {
        return right > 0L && left < Long.MIN_VALUE + right ? Long.MIN_VALUE : left - right;
    }

}

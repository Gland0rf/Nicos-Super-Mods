package com.nico.client.utils.tradeprot.valuation;

import com.nico.client.utils.BazaarService;
import com.nico.client.utils.tradeprot.AuctionHouseService;
import com.nico.client.utils.tradeprot.valuation.records.AppliedGemstone;
import com.nico.client.utils.tradeprot.valuation.records.ItemValueEstimate;
import com.nico.client.utils.tradeprot.valuation.records.SkyblockItemData;
import com.nico.client.utils.tradeprot.valuation.records.ValueComponent;
import net.fabricmc.fabric.api.lookup.v1.item.ItemApiLookup;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class ItemValueService {
    private static final List<String> MASTER_STAR_IDS = List.of(
            "FIRST_MASTER_STAR",  "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"
    );

    private final BazaarService bazaarService;
    private final SkyblockItemDataReader itemReader;

    public ItemValueService(BazaarService bazaarService) {
        this(bazaarService, new SkyblockItemDataReader());
    }

    public ItemValueService(BazaarService bazaarService, SkyblockItemDataReader itemReader) {
        if (bazaarService == null) throw new IllegalArgumentException("bazaarService cannot be null");
        if (itemReader == null) throw new IllegalArgumentException("itemReader cannot be null");
        this.bazaarService = bazaarService;
        this.itemReader = itemReader;
    }

    public CompletableFuture<ItemValueEstimate> estimate(ItemStack stack) {
        SkyblockItemData item = itemReader.read(stack);
        if (!item.valid()) {
            return CompletableFuture.completedFuture(new ItemValueEstimate(
                    item, 0L, ItemValueEstimate.Confidence.NONE, List.of(), List.of("Missing Skyblock item id")
            ));
        }

        BazaarService.BazaarSnapshot bazaar;
        try {
            bazaar = bazaarService.getSnapshot();
        } catch (IOException ignored) {
            bazaar = null;
        }

        EstimateBuilder builder = new EstimateBuilder(item);
        boolean bazaarBase = bazaar != null && builder.addBazaarBase(bazaar);
        if (bazaar != null) builder.addKnownComponents(bazaar);

        if (bazaarBase) return CompletableFuture.completedFuture(builder.finish(ItemValueEstimate.Confidence.HIGH));

        return AuctionHouseService.lowestBin(item.internalId(), item.displayName()).thenApply(lowestBin -> {
            if (lowestBin.available()) {
                builder.addMarketFallback(lowestBin.coins());
                return builder.finish(ItemValueEstimate.Confidence.LOW);
            }
            builder.unknown("No Bazaar product or active BIN was found for the base item");
            return builder.finish(ItemValueEstimate.Confidence.NONE);
        });
    }

    private static long bazaarPrice(BazaarService.BazaarSnapshot snapshot, String productId) {
        if (snapshot == null || productId == null || productId.isBlank()) return 0L;
        Optional<BazaarService.BazaarProduct> product = snapshot.getProduct(productId);
        if (product.isEmpty()) return 0L;
        double value = product.get().getInstantBuyPrice();
        return Double.isFinite(value) && value > 0.0D ? Math.max(0L, Math.round(value)) : 0L;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String enchantProductId(String enchantment, int level) {
        return "ENCHANTMENT" + normalize(enchantment) + "_" + level;
    }

    private static final class EstimateBuilder {
        private final SkyblockItemData item;
        private final List<ValueComponent> components = new ArrayList<>();
        private final List<String> unknowns = new ArrayList<>();
        private long total;
        private boolean auctionFallback;

        private EstimateBuilder(SkyblockItemData item) { this.item = item; }

        boolean addBazaarBase(BazaarService.BazaarSnapshot bazaar) {
            long unit = bazaarPrice(bazaar, item.internalId());
            if (unit <= 0L) return false;
            add("base", item.displayName().isBlank() ? item.internalId() : item.displayName(), unit, item.count(), true, "Hypixel Bazaar");
            return true;
        }

        void addMarketFallback(long coins) {
            auctionFallback = true;
            add("market_fallback", "Active BIN market value", coins, item.count(), true, "Hypixel Auction House");
        }

        void addKnownComponents(BazaarService.BazaarSnapshot bazaar) {
            if (item.rarityUpgrades() > 0) addBazaarComponent(bazaar, "RECOMBOBULATOR_3000", "Recombobulator 3000", item.rarityUpgrades());

            int hotPotatoes = Math.min(10, item.hotPotatoCount());
            int fumings = Math.max(0, item.hotPotatoCount() - 10);
            if (hotPotatoes > 0) addBazaarComponent(bazaar, "HOT_POTATO_BOOK", "Hot Potato Book", hotPotatoes);
            if (fumings > 0) addBazaarComponent(bazaar, "FUMING_POTATO_BOOK", "Fuming Potato Book", fumings);
            if (item.normalStars() > 0) unknown("Regular dungeon-star essence cost is not priced yet");

            for (int i = 0; i < item.masterStars(); i++) addBazaarComponent(bazaar, MASTER_STAR_IDS.get(i), "Master Star " + (i + 1), 1);
            if (item.artOfWarCount() > 0) addBazaarComponent(bazaar, "THE_ART_OF_WAR", "The Art of War", item.artOfWarCount());
            if (item.farmingForDummiesCount() > 0) addBazaarComponent(bazaar, "FARMING_FOR_DUMMIES", "Farming for Dummies", item.farmingForDummiesCount());
            if (item.transmissionTunerCount() > 0) addBazaarComponent(bazaar, "TRANSMISSION_TUNER", "Transmission Tuner", item.transmissionTunerCount());
            if (item.woodSingularityApplied()) addBazaarComponent(bazaar, "WOOD_SINGULARITY", "Wood Singularity", 1);
            if (!item.powerScroll().isBlank()) addBazaarComponent(bazaar, normalize(item.powerScroll()), "Power Scroll", 1);

            for (String scroll : item.abilityScrolls()) addBazaarComponent(bazaar, normalize(scroll), prettify(scroll), 1);
            for (Map.Entry<String, Integer> enchantment : item.enchantments().entrySet()) {
                addBazaarComponent(bazaar, enchantProductId(enchantment.getKey(), enchantment.getValue()),
                        prettify(enchantment.getKey()) + " " + enchantment.getValue(), 1);
            }
            for (AppliedGemstone gemstone : item.gemstones()) {
                String productId = gemstone.bazaarProductId();
                if (!productId.isBlank()) addBazaarComponent(bazaar, productId, prettify(productId), 1);
            }
        }

        void addBazaarComponent(BazaarService.BazaarSnapshot bazaar, String productId, String label, int quantity) {
            long unit = bazaarPrice(bazaar, productId);
            if (unit <= 0L) {
                unknown(label + " has no Bazaar price");
                return;
            }
            add(productId, label, unit, quantity, !auctionFallback, "Hypixel Bazaar");
        }

        void add(String key, String label, long unitCoins, int quantity, boolean included, String source) {
            long coins;
            try {
                coins = Math.multiplyExact(unitCoins, Math.max(1, quantity));
            } catch (ArithmeticException ignored) {
                coins = Long.MAX_VALUE;
            }
            components.add(new ValueComponent(key, label, unitCoins, quantity, included, source));
            if (included) total = saturatingAdd(total, coins);
        }

        void unknown(String value) {
            if (value != null && !value.isBlank() && !unknowns.contains(value)) unknowns.add(value);
        }

        ItemValueEstimate finish(ItemValueEstimate.Confidence requested) {
            ItemValueEstimate.Confidence confidence = requested;
            if (!unknowns.isEmpty() && confidence == ItemValueEstimate.Confidence.HIGH) {
                confidence = ItemValueEstimate.Confidence.MEDIUM;
            }
            if (total <= 0L) confidence = ItemValueEstimate.Confidence.NONE;
            return new ItemValueEstimate(item, total, confidence, components, unknowns);
        }

        private static long saturatingAdd(long left, long right) {
            return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
        }

        private static String prettify(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', ' ');
            StringBuilder result = new StringBuilder();
            for (String word : normalized.split("\\s+")) {
                if (word.isBlank()) continue;
                if (result.length() > 0) result.append(' ');
                result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
            return result.toString();
        }
    }
}

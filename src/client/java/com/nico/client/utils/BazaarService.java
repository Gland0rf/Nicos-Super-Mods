package com.nico.client.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

public class BazaarService {

    private static final String BAZAAR_PATH = "/v2/skyblock/bazaar";

    private final HypixelApiClient apiClient;
    private final long cacheMillies;

    private BazaarSnapshot cachedSnapshot;
    private long cachedAtMillis;

    public BazaarService(HypixelApiClient apiClient) {
        this(apiClient, 60_000L);
    }

    public BazaarService(HypixelApiClient apiClient, long cacheMillies) {
        if (apiClient == null) {
            throw new IllegalArgumentException("apiClient cannot be null");
        }

        this.apiClient = apiClient;
        this.cacheMillies = Math.max(5_000L, cacheMillies);
    }

    public synchronized BazaarSnapshot getSnapshot() throws IOException {
        long now = System.currentTimeMillis();

        if (cachedSnapshot != null && now - cachedAtMillis < cacheMillies) {
            return cachedSnapshot;
        }

        cachedSnapshot = fetchLatestSnapshot();
        cachedAtMillis = now;
        return cachedSnapshot;
    }

    public synchronized void invalidateCache() {
        cachedSnapshot = null;
        cachedAtMillis = 0L;
    }

    private BazaarSnapshot fetchLatestSnapshot() throws IOException {
        JsonObject root = apiClient.getJson(BAZAAR_PATH);

        if (root.has("success") && !root.get("success").getAsBoolean()) {
            throw new IOException("Hypixel Bazaar API returned success=false");
        }

        long lastUpdated = getLong(root, "lastUpdated", 0L);

        if (!root.has("products") || !root.get("products").isJsonObject()) {
            throw new IOException("Hypixel Bazaar API response had no products object");
        }

        JsonObject productsJson = root.getAsJsonObject("products");
        Map<String, BazaarProduct> products = new HashMap<>();

        for (Map.Entry<String, JsonElement> entry : productsJson.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }

            JsonObject productJson = entry.getValue().getAsJsonObject();
            JsonObject quickStatus = productJson.has("quick_status")
                    && productJson.get("quick_status").isJsonObject()
                    ? productJson.getAsJsonObject("quick_status")
                    : new JsonObject();

            String productId = getString(quickStatus, "productId", null);

            if (productId == null) {
                productId = getString(productJson, "product_id", entry.getKey());
            }

            BazaarProduct product = new BazaarProduct(
                    productId,
                    getDouble(quickStatus, "sellPrice", 0.0D),
                    getDouble(quickStatus, "buyPrice", 0.0D),
                    getLong(quickStatus, "sellVolume", 0L),
                    getLong(quickStatus, "buyVolume", 0L),
                    getLong(quickStatus, "sellMovingWeek", 0L),
                    getLong(quickStatus, "buyMovingWeek", 0L),
                    getLong(quickStatus, "sellOrders", 0L),
                    getLong(quickStatus, "buyOrders", 0L)
            );

            products.put(productId, product);
        }

        return new BazaarSnapshot(lastUpdated, products);
    }

    /**
     * INSTANT_SELL:
     *   Price you care about for minion output.
     *   "If I sell this output instantly, roughly how many coins do I get?"
     *
     * INSTANT_BUY:
     *   Price you care about for upgrade costs.
     *   "If I buy these upgrade materials instantly, roughly how many coins do I pay?"
     */
    public OptionalDouble getPrice(String productId, PriceMode mode) throws IOException {
        Optional<BazaarProduct> product = getSnapshot().getProduct(productId);

        if (!product.isPresent()) {
            return OptionalDouble.empty();
        }

        BazaarProduct p = product.get();

        switch (mode) {
            case INSTANT_BUY:
                return p.getInstantBuyPrice() > 0
                        ? OptionalDouble.of(p.getInstantBuyPrice())
                        : OptionalDouble.empty();

            case INSTANT_SELL:
            default:
                return p.getInstantSellPrice() > 0
                        ? OptionalDouble.of(p.getInstantSellPrice())
                        : OptionalDouble.empty();
        }
    }

    public OptionalDouble getInstantSellPrice(String productId) throws IOException {
        return getPrice(productId, PriceMode.INSTANT_SELL);
    }

    public OptionalDouble getInstantBuyPrice(String productId) throws IOException {
        return getPrice(productId, PriceMode.INSTANT_BUY);
    }

    private static String getString(JsonObject object, String key, String defaultValue) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }

        try {
            return object.get(key).getAsString();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private static double getDouble(JsonObject object, String key, double defaultValue) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }

        try {
            return object.get(key).getAsDouble();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    private static long getLong(JsonObject object, String key, long defaultValue) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return defaultValue;
        }

        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException ignored) {
            return defaultValue;
        }
    }

    public enum PriceMode {
        INSTANT_SELL,
        INSTANT_BUY
    }

    public static final class BazaarSnapshot {
        private final long lastUpdated;
        private final Map<String, BazaarProduct> products;

        private BazaarSnapshot(long lastUpdated, Map<String, BazaarProduct> products) {
            this.lastUpdated = lastUpdated;
            this.products = products;
        }

        public long getLastUpdated() {
            return lastUpdated;
        }

        public Map<String, BazaarProduct> getProducts() {
            return products;
        }

        public Optional<BazaarProduct> getProduct(String productId) {
            if (productId == null) {
                return Optional.empty();
            }

            return Optional.ofNullable(products.get(productId));
        }

        public boolean hasProduct(String productId) {
            return productId != null && products.containsKey(productId);
        }
    }

    public static final class BazaarProduct {
        private final String productId;

        private final double instantSellPrice;

        private final double instantBuyPrice;

        private final long sellVolume;
        private final long buyVolume;
        private final long sellMovingWeek;
        private final long buyMovingWeek;
        private final long sellOrders;
        private final long buyOrders;

        private BazaarProduct(
                String productId,
                double instantSellPrice,
                double instantBuyPrice,
                long sellVolume,
                long buyVolume,
                long sellMovingWeek,
                long buyMovingWeek,
                long sellOrders,
                long buyOrders
        ) {
            this.productId = productId;
            this.instantSellPrice = instantSellPrice;
            this.instantBuyPrice = instantBuyPrice;
            this.sellVolume = sellVolume;
            this.buyVolume = buyVolume;
            this.sellMovingWeek = sellMovingWeek;
            this.buyMovingWeek = buyMovingWeek;
            this.sellOrders = sellOrders;
            this.buyOrders = buyOrders;
        }

        public String getProductId() {
            return productId;
        }

        public double getInstantSellPrice() {
            return instantSellPrice;
        }

        public double getInstantBuyPrice() {
            return instantBuyPrice;
        }

        public long getSellVolume() {
            return sellVolume;
        }

        public long getBuyVolume() {
            return buyVolume;
        }

        public long getSellMovingWeek() {
            return sellMovingWeek;
        }

        public long getBuyMovingWeek() {
            return buyMovingWeek;
        }

        public long getSellOrders() {
            return sellOrders;
        }

        public long getBuyOrders() {
            return buyOrders;
        }
    }
}

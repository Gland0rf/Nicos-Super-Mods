package com.nico.client.utils.tradeprot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nico.client.wiki.WikiHttp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Lightweight fallback for Wiki Bazaar values when the host mod did not inject a BazaarService. */
public final class WikiBazaarService {
    private static final URI ENDPOINT = URI.create("https://api.hypixel.net/v2/skyblock/bazaar");
    private static final long CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static volatile Map<String, Product> cachedProducts = Map.of();
    private static volatile long cachedAt;
    private static volatile CompletableFuture<Map<String, Product>> refresh;

    private WikiBazaarService() { }

    public static CompletableFuture<Map<String, Product>> products() {
        long now = System.currentTimeMillis();
        Map<String, Product> current = cachedProducts;
        if (!current.isEmpty() && now - cachedAt < CACHE_TTL_MILLIS) {
            return CompletableFuture.completedFuture(current);
        }

        synchronized (WikiBazaarService.class) {
            now = System.currentTimeMillis();
            current = cachedProducts;
            if (!current.isEmpty() && now - cachedAt < CACHE_TTL_MILLIS) {
                return CompletableFuture.completedFuture(current);
            }
            if (refresh != null && !refresh.isDone()) {
                return refresh;
            }

            HttpRequest request = WikiHttp.request(ENDPOINT, Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            refresh = HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        if (response.statusCode() < 200 || response.statusCode() >= 300) {
                            throw new IllegalStateException("Hypixel Bazaar HTTP " + response.statusCode());
                        }
                        return parse(response.body());
                    })
                    .whenComplete((products, throwable) -> {
                        synchronized (WikiBazaarService.class) {
                            if (throwable == null && products != null && !products.isEmpty()) {
                                cachedProducts = products;
                                cachedAt = System.currentTimeMillis();
                            }
                            refresh = null;
                        }
                    });
            return refresh;
        }
    }

    public static synchronized void invalidate() {
        cachedProducts = Map.of();
        cachedAt = 0L;
        refresh = null;
    }

    private static Map<String, Product> parse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (root.has("success") && !root.get("success").getAsBoolean()) {
            throw new IllegalStateException("Hypixel Bazaar response reported failure");
        }
        JsonObject products = root.has("products") && root.get("products").isJsonObject()
                ? root.getAsJsonObject("products")
                : null;
        if (products == null) {
            throw new IllegalStateException("Hypixel Bazaar response contained no products");
        }

        Map<String, Product> result = new HashMap<>();
        for (Map.Entry<String, JsonElement> entry : products.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject product = entry.getValue().getAsJsonObject();
            JsonObject quick = product.has("quick_status") && product.get("quick_status").isJsonObject()
                    ? product.getAsJsonObject("quick_status")
                    : null;
            if (quick == null) {
                continue;
            }

            String id = stringValue(product, "product_id");
            if (id.isBlank()) {
                id = entry.getKey();
            }
            double instantBuy = doubleValue(quick, "sellPrice");
            double instantSell = doubleValue(quick, "buyPrice");
            if (!Double.isFinite(instantBuy)) {
                instantBuy = 0.0D;
            }
            if (!Double.isFinite(instantSell)) {
                instantSell = 0.0D;
            }
            result.put(id.toUpperCase(Locale.ROOT), new Product(instantBuy, instantSell));
        }
        return Map.copyOf(result);
    }

    private static String stringValue(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsString()
                    : "";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static double doubleValue(JsonObject object, String key) {
        try {
            return object != null && object.has(key) && !object.get(key).isJsonNull()
                    ? object.get(key).getAsDouble()
                    : 0.0D;
        } catch (RuntimeException ignored) {
            return 0.0D;
        }
    }

    public record Product(double instantBuyPrice, double instantSellPrice) { }
}

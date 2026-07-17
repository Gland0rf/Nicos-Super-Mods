package com.nico.client.minions.base;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MinionDataRegistry {
    private final Map<String, MinionData> minionsById;

    private MinionDataRegistry(Map<String, MinionData> minionsById) {
        this.minionsById = Collections.unmodifiableMap(new LinkedHashMap<>(minionsById));
    }

    public static MinionDataRegistry loadFromResources(String resourcePath) throws IOException {
        InputStream stream = MinionDataRegistry.class.getResourceAsStream(resourcePath);

        if (stream == null) {
            throw new IOException("Could not find minion data resource: " + resourcePath);
        }

        String json = readFully(stream);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        Map<String, MinionData> result = new LinkedHashMap<>();

        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String minionId = entry.getKey();
            JsonObject minionJson = entry.getValue().getAsJsonObject();

            int actionsPerCycle = getInt(minionJson, "actionsPerCycle", 2);

            Map<Integer, Double> actionTimes =
                    parseActionTimes(minionJson.getAsJsonObject("tiers"));

            List<MinionOutputDrop> baseDrops =
                    parseDrops(minionJson.getAsJsonArray("baseDrops"));

            String displayName = getString(minionJson, "displayName", minionId + " Minion");
            String category = getString(minionJson, "category", "UNKNOWN");
            int maxTier = getInt(minionJson, "maxTier", actionTimes.keySet().stream().max(Integer::compareTo).orElse(1));
            boolean simpleEstimatorEnabled = getBoolean(minionJson, "simpleEstimatorEnabled", true);
            String tierDataQuality = getString(minionJson, "tierDataQuality", "unknown");

            MinionDefinition definition = new MinionDefinition(
                    minionId,
                    actionsPerCycle,
                    actionTimes,
                    baseDrops
            );

            Map<Integer, MinionUpgradeRecipe> upgradeRecipesByFromTier =
                    parseUpgradeRecipes(minionJson);

            result.put(
                    minionId,
                    new MinionData(
                            minionId,
                            displayName,
                            category,
                            maxTier,
                            simpleEstimatorEnabled,
                            tierDataQuality,
                            definition,
                            upgradeRecipesByFromTier
                    )
            );
        }

        return new MinionDataRegistry(result);
    }

    public Collection<MinionData> getAll() {
        return minionsById.values();
    }

    private static Map<Integer, Double> parseActionTimes(JsonObject tiersJson) {
        Map<Integer, Double> result = new LinkedHashMap<>();

        for (Map.Entry<String, JsonElement> entry : tiersJson.entrySet()) {
            int tier = Integer.parseInt(entry.getKey());
            JsonObject tierJson = entry.getValue().getAsJsonObject();

            result.put(
                    tier,
                    getDouble(tierJson, "secondsBetweenActions", -1.0D)
            );
        }

        return result;
    }

    private static List<MinionOutputDrop> parseDrops(JsonArray dropsJson) {
        List<MinionOutputDrop> result = new ArrayList<>();

        for (JsonElement element : dropsJson) {
            JsonObject dropJson = element.getAsJsonObject();

            result.add(new MinionOutputDrop(
                    getString(dropJson, "productId", null),
                    getDouble(dropJson, "amountPerCycle", 1.0D),
                    getBoolean(dropJson, "affectedByOutputMultiplier", true)
            ));
        }

        return result;
    }

    private static Map<Integer, MinionUpgradeRecipe> parseUpgradeRecipes(JsonObject minionJson) {
        Map<Integer, MinionUpgradeRecipe> result = new LinkedHashMap<>();

        if (minionJson == null || !minionJson.has("upgradeCosts")) return result;

        JsonObject upgradeCostsJson = minionJson.getAsJsonObject("upgradeCosts");

        for (Map.Entry<String, JsonElement> entry : upgradeCostsJson.entrySet()) {
            String key = entry.getKey();

            String[] parts = key.split("_to_");

            if (parts.length != 2) continue;

            int fromTier = Integer.parseInt(parts[0]);
            int toTier = Integer.parseInt(parts[1]);

            JsonObject recipeJson = entry.getValue().getAsJsonObject();
            JsonArray materialsJson = recipeJson.getAsJsonArray("materials");

            List<MinionUpgradeMaterial> materials = new ArrayList<>();

            if (materialsJson != null) {
                for (JsonElement materialElement : materialsJson) {
                    JsonObject materialJson = materialElement.getAsJsonObject();

                    materials.add(new MinionUpgradeMaterial(
                            getString(materialJson, "productId", null),
                            getString(materialJson, "itemName", "Unknown Item"),
                            getDouble(materialJson, "amount", 0.0D)
                    ));
                }
            }

            result.put(
                    fromTier,
                    new MinionUpgradeRecipe(
                            fromTier,
                            toTier,
                            materials
                    )
            );
        }

        return Collections.unmodifiableMap(result);
    }

    private static String readFully(InputStream stream) throws IOException {
        StringBuilder builder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            String line;

            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }

        return builder.toString();
    }

    private static int getInt(JsonObject object, String key, int defaultValue) {
        return object != null && object.has(key)
                ? object.get(key).getAsInt()
                : defaultValue;
    }

    private static double getDouble(JsonObject object, String key, double defaultValue) {
        return object != null && object.has(key)
                ? object.get(key).getAsDouble()
                : defaultValue;
    }

    private static boolean getBoolean(JsonObject object, String key, boolean defaultValue) {
        return object != null && object.has(key)
                ? object.get(key).getAsBoolean()
                : defaultValue;
    }

    private static String getString(JsonObject object, String key, String defaultValue) {
        return object != null && object.has(key) && !object.get(key).isJsonNull()
                ? object.get(key).getAsString()
                : defaultValue;
    }

    public Optional<MinionData> getDataById(String minionId) {
        if (minionId == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(minionsById.get(minionId));
    }

    public Optional<MinionDefinition> getById(String minionId) {
        return getDataById(minionId).map(MinionData::definition);
    }

    public Collection<MinionData> getAllData() {
        return minionsById.values();
    }
}
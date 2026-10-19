package com.nico.client.configuration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.Category;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class FeatureRegistry {
    private record Placement(Field categoryField, Field featureField, FeatureId id) {
        String category() { return categoryField.getName(); }
        String feature() { return featureField.getName(); }
    }

    private static final List<Placement> PLACEMENTS = discover();
    private static final Map<Class<?>, Placement> BY_TYPE = indexTypes();

    private FeatureRegistry() { }

    private static List<Placement> discover() {
        List<Placement> placements = new ArrayList<>();
        Map<String, String> ids = new HashMap<>();
        for (Field category : NsmConfig.class.getFields()) {
            if (!category.isAnnotationPresent(Category.class)) continue;
            for (Field feature : category.getType().getFields()) {
                FeatureId id = feature.getAnnotation(FeatureId.class);
                if (id == null) continue;
                if (!feature.isAnnotationPresent(Accordion.class)) {
                    throw new IllegalStateException("Missing @Accordion on " + category.getName() + "." + feature.getName());
                }
                String previous = ids.putIfAbsent(id.value(), category.getName());
                if (previous != null) {
                    throw new IllegalStateException("Duplicate feature ID " + id.value() + " (" + previous + ", " + category.getName() + ")");
                }
                placements.add(new Placement(category, feature, id));
            }
        }
        return List.copyOf(placements);
    }

    private static Map<Class<?>, Placement> indexTypes() {
        Map<Class<?>, Placement> types = new HashMap<>();
        for (Placement placement : PLACEMENTS) {
            if (types.putIfAbsent(placement.featureField().getType(), placement) != null) {
                throw new IllegalStateException("Feature settings type is reused: " + placement.featureField.getType());
            }
        }
        return Map.copyOf(types);
    }

    public static <T> T get(NsmConfig config, Class<T> type) {
        Placement placement = BY_TYPE.get(type);
        if (placement == null) throw new IllegalArgumentException("Unregistered feature settings: " + type.getName());
        try {
            Object category = placement.categoryField.get(config);
            if (category == null) throw new IllegalStateException("Missing category: " + placement.category());
            Object settings = placement.featureField.get(category);
            if (settings == null) throw new IllegalStateException("Missing feature: " + placement.id.value());
            return type.cast(settings);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Cannot access " + placement.id.value(), exception);
        }
    }

    /**
     * Preserve saved settings from older or future category placements.
     * The current placement always wins; otherwise search any old category.
     * Legacy field names are supported for renamed features.
     */
    public static void migrate(JsonObject root) {
        for (Placement placement : PLACEMENTS) {
            JsonObject destination = getOrCreateObject(root, placement.category());
            if (destination.has(placement.feature())) {
                migrateNestedLayout(destination.get(placement.feature()), placement.featureField.getType());
                continue;
            }

            JsonElement oldSettings = null;
            String matchedName = null;
            // The previous menu used "other" for most features; prefer it if present.
            List<String> names = new ArrayList<>();
            names.add(placement.feature());
            if (!names.contains(placement.id.value())) names.add(placement.id.value());
            for (String alias : placement.id.legacyNames()) {
                if (!names.contains(alias)) names.add(alias);
            }
            // A field can be renamed without changing its category. Try its old IDs here first.
            for (String name : names) {
                if (!name.equals(placement.feature()) && destination.has(name)) {
                    oldSettings = destination.get(name);
                    matchedName = name;
                    break;
                }
            }
            JsonObject oldOther = getObject(root, "other");
            if (oldSettings == null && oldOther != null && oldOther != destination) {
                for (String name : names) {
                    if (oldOther.has(name)) { oldSettings = oldOther.get(name); matchedName = name; break; }
                }
            }
            if (oldSettings == null) {
                outer:
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    if (entry.getKey().equals(placement.category()) || !entry.getValue().isJsonObject()) continue;
                    JsonObject oldCategory = entry.getValue().getAsJsonObject();
                    for (String name : names) {
                        if (oldCategory.has(name)) {
                            oldSettings = oldCategory.get(name);
                            matchedName = name;
                            break outer;
                        }
                    }
                }
            }
            if (oldSettings == null || oldSettings.isJsonNull()) continue;
            JsonElement value = oldSettings.deepCopy();
            if ("lagMonitor".equals(placement.id.value()) && "dungeonLagMonitor".equals(matchedName)) {
                migrateOldDungeonLagMonitor(value);
            }
            destination.add(placement.feature(), value);
            migrateNestedLayout(value, placement.featureField.getType());
        }
    }

    /**
     * Also support moving individual options into/out of a nested @Accordion.
     * An existing value follows its unique Java field name to its new location.
     */
    private static void migrateNestedLayout(JsonElement element, Class<?> settingsType) {
        if (element == null || !element.isJsonObject()) return;
        JsonObject settings = element.getAsJsonObject();
        Map<String, List<JsonElement>> leaves = new HashMap<>();
        collectLeaves(settings, leaves);
        placeLeaves(settings ,settingsType, leaves);
    }

    private static void collectLeaves(JsonObject object, Map<String, List<JsonElement>> leaves) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                collectLeaves(value.getAsJsonObject(), leaves);
            } else if (!value.isJsonNull()) {
                leaves.computeIfAbsent(entry.getKey(), unused -> new ArrayList<>()).add(value);
            }
        }
    }

    private static void placeLeaves(JsonObject target, Class<?> settingsType, Map<String, List<JsonElement>> leaves) {
        for (Field field : settingsType.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
            String name = field.getName();
            if (field.isAnnotationPresent(Accordion.class)) {
                JsonObject nested = getOrCreateObject(target, name);
                placeLeaves(nested, field.getType(), leaves);
            } else if (!target.has(name)) {
                List<JsonElement> candidates = leaves.get(name);
                if (candidates != null && candidates.size() == 1) {
                    target.add(name, candidates.get(0).deepCopy());
                }
            }
        }
    }

    // Support the oldest Lag Monitor format without changing existing modern settings.
    private static void migrateOldDungeonLagMonitor(JsonElement source) {
        if (!source.isJsonObject()) return;
        JsonObject old = source.getAsJsonObject();
        JsonElement dungeonOnly = old.get("onlyShowInDungeons");
        if (dungeonOnly == null || !dungeonOnly.isJsonPrimitive() || !dungeonOnly.getAsJsonPrimitive().isBoolean()) return;
        JsonObject visibility = getOrCreateObject(old, "visibility");
        if (!visibility.has("showInDungeons")) visibility.addProperty("showInDungeons", true);
        if (!visibility.has("showOnHypixelOutsideDungeons")) visibility.addProperty("showOnHypixelOutsideDungeons", !dungeonOnly.getAsBoolean());
        if (!visibility.has("showOnOtherServers")) visibility.addProperty("showOnOtherServers", false);
    }

    private static JsonObject getObject(JsonObject root, String name) {
        JsonElement element = root.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonObject getOrCreateObject(JsonObject root, String name) {
        JsonObject result = getObject(root, name);
        if (result == null) { result = new JsonObject(); root.add(name, result); }
        return result;
    }
}

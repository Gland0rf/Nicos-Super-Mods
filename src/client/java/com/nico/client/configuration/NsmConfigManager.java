package com.nico.client.configuration;

import com.google.gson.*;
import com.nico.client.configuration.category.CategoryDungeons;
import com.nico.client.configuration.category.CategoryOther;
import com.nico.client.utils.AtomicFiles;
import io.github.notenoughupdates.moulconfig.gui.GuiContext;
import io.github.notenoughupdates.moulconfig.gui.GuiElementComponent;
import io.github.notenoughupdates.moulconfig.gui.MoulConfigEditor;
import io.github.notenoughupdates.moulconfig.observer.PropertyTypeAdapterFactory;
import io.github.notenoughupdates.moulconfig.platform.MoulConfigScreenComponent;
import io.github.notenoughupdates.moulconfig.processor.BuiltinMoulConfigGuis;
import io.github.notenoughupdates.moulconfig.processor.ConfigProcessorDriver;
import io.github.notenoughupdates.moulconfig.processor.MoulConfigProcessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class NsmConfigManager {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapterFactory(new PropertyTypeAdapterFactory())
            .create();

    private static boolean initialized;
    private static MoulConfigProcessor<NsmConfig> processor;
    private static MoulConfigEditor<NsmConfig> editor;

    private NsmConfigManager() {
    }

    public static synchronized void init() {
        if (initialized && processor != null) return;

        NsmConfig loadedConfig = loadConfig();
        NsmConfig.INSTANCE = loadedConfig == null ? new NsmConfig() : loadedConfig;

        processor = new MoulConfigProcessor<>(NsmConfig.INSTANCE);
        BuiltinMoulConfigGuis.addProcessors(processor);

        ConfigProcessorDriver driver = new ConfigProcessorDriver(processor);
        driver.warnForPrivateFields = false;
        driver.processConfig(NsmConfig.INSTANCE);

        initialized = true;
        save();
    }

    public static Screen createScreen(Screen parent) {
        if (!initialized || processor == null) init();

        if (editor == null) {
            editor = new MoulConfigEditor<>(processor);
        }

        return new MoulConfigScreenComponent(
                Component.literal("Nico's Super Mods"),
                new GuiContext(new GuiElementComponent(editor)),
                parent
        ) {
            @Override
            public void removed() {
                NsmConfigManager.save();
                super.removed();
            }
        };
    }

    public static synchronized void save() {
        if (!initialized) {
            init();
            return;
        }

        Path path = getConfigFile().toPath();

        try {
            AtomicFiles.writeAtomically(path, temporary -> {
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    GSON.toJson(NsmConfig.INSTANCE, writer);
                }
            });
        } catch (IOException exception) {
            System.err.println("[NSM] Could not save config: " + exception.getMessage());
        }
    }

    private static NsmConfig loadConfig() {
        File file = getConfigFile();
        if (!file.exists()) return null;

        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            JsonElement rawConfig = JsonParser.parseReader(reader);
            NsmConfig loadedConfig = GSON.fromJson(rawConfig, NsmConfig.class);
            migrateLegacyLagMonitor(loadedConfig, rawConfig);
            return loadedConfig;
        } catch (IOException | RuntimeException exception) {
            System.err.println("[NSM] Could not load config; using defaults: " + exception.getMessage());
            return null;
        }
    }

    private static void migrateLegacyLagMonitor(NsmConfig config, JsonElement rawConfig) {
        if (config == null || rawConfig == null || !rawConfig.isJsonObject()) {
            return;
        }

        JsonObject root = rawConfig.getAsJsonObject();
        JsonObject other = getObject(root, "other");
        if (other != null && other.has("lagMonitor")) return;

        JsonObject dungeons = getObject(root, "dungeons");
        if (dungeons == null || !dungeons.has("dungeonLagMonitor")) return;

        JsonElement legacyElement = dungeons.get("dungeonLagMonitor");
        CategoryOther.LagMonitor migrated = GSON.fromJson(legacyElement, CategoryOther.LagMonitor.class);
        if (migrated == null) return;

        if (migrated.visibility == null) {
            migrated.visibility = new CategoryOther.LagMonitor.Visibility();
        }
        if (migrated.design == null) {
            migrated.design = new CategoryOther.LagMonitor.Design();
        }

        if (legacyElement.isJsonObject()) {
            JsonObject legacyObject = legacyElement.getAsJsonObject();
            JsonElement onlyDungeons = legacyObject.get("onlyShowInDungeons");
            if (onlyDungeons != null && onlyDungeons.isJsonPrimitive() && onlyDungeons.getAsJsonPrimitive().isBoolean()) {
                migrated.visibility.showInDungeons = true;
                migrated.visibility.showOnHypixelOutsideDungeons = !onlyDungeons.getAsBoolean();
                migrated.visibility.showOnOtherServers = false;
            }
        }

        if (config.other == null) {
            config.other = new CategoryOther();
        }
        config.other.lagMonitor = migrated;
    }

    private static JsonObject getObject(JsonObject parent, String name) {
        if (parent == null || !parent.has(name) || !parent.get(name).isJsonObject()) return null;
        return parent.getAsJsonObject(name);
    }

    private static File getConfigFile() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("nicos_super_mods")
                .resolve("config.json")
                .toFile();
    }
}
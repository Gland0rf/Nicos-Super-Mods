package com.nico.client.configuration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public final class NsmConfigManager {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .registerTypeAdapterFactory(new PropertyTypeAdapterFactory())
            .create();

    private static NsmConfig config;
    private static MoulConfigProcessor<NsmConfig> processor;
    private static MoulConfigEditor<NsmConfig> editor;

    private NsmConfigManager() {
    }

    public static void init() {
        File file = getConfigFile();

        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                config = GSON.fromJson(reader, NsmConfig.class);
            } catch (IOException exception) {
                exception.printStackTrace();
            }
        }

        if (config == null) {
            config = new NsmConfig();
        }

        NsmConfig.INSTANCE = config;

        processor = new MoulConfigProcessor<>(config);
        BuiltinMoulConfigGuis.addProcessors(processor);

        ConfigProcessorDriver driver = new ConfigProcessorDriver(processor);
        driver.warnForPrivateFields = false;
        driver.processConfig(config);

        save();
    }

    public static Screen createScreen(Screen parent) {
        if (config == null || processor == null) {
            init();
        }

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

    public static NsmConfig getConfig() {
        if (config == null) {
            init();
        }

        return config;
    }

    public static void save() {
        File file = getConfigFile();
        file.getParentFile().mkdirs();

        try (FileWriter writer = new FileWriter(file)) {
            GSON.toJson(getConfig(), writer);
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    private static File getConfigFile() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("nicos_super_mods")
                .resolve("config.json")
                .toFile();
    }
}
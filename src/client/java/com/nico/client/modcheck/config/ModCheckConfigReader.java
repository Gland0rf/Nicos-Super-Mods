package com.nico.client.modcheck.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModCheckConfigReader {

    private ModCheckConfigReader() { }

    public static ModCheckSettings load() {
        ModCheckSettings defaults = ModCheckSettings.defaults();

        Path file = FabricLoader.getInstance()
                .getConfigDir()
                .resolve("nicos_super_mods")
                .resolve("config.json");

        if (!Files.isRegularFile(file)) {
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);

            if (!parsed.isJsonObject()) {
                return defaults;
            }

            JsonObject root = parsed.getAsJsonObject();

            JsonObject other = getObject(root, "other");
            JsonObject modCheck = getObject(other, "modCheck");

            if (modCheck == null) return defaults;

            return new ModCheckSettings(
                    getBoolean(
                            modCheck,
                            "enabled",
                            defaults.enabled()
                    ),
                    getBoolean(
                            modCheck,
                            "showWarningScreen",
                            defaults.showWarningScreen()
                    ),
                    getBoolean(
                            modCheck,
                            "warnAboutUnknownMods",
                            defaults.warnAboutUnknownMods()
                    )
            );
        } catch (Exception exception) {
            System.err.println(
                    "[NSM ModCheck] Could not read startup "
                            + "configuration; using defaults: "
                            + exception.getClass().getSimpleName()
                            + ": "
                            + exception.getMessage()
            );

            return defaults;
        }
    }

    private static JsonObject getObject(JsonObject parent, String name) {
        if (parent == null) {
            return null;
        }

        JsonElement element = parent.get(name);

        if (element == null || !element.isJsonObject()) {
            return null;
        }

        return element.getAsJsonObject();
    }

    private static boolean getBoolean(JsonObject object, String name, boolean fallback) {
        if (object == null) {
            return fallback;
        }

        JsonElement element = object.get(name);

        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }

        return element.getAsBoolean();
    }

}

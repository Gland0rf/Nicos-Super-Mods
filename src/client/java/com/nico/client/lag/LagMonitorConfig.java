package com.nico.client.lag;

import com.google.gson.*;
import com.nico.client.configuration.category.CategoryDungeons;
import com.nico.client.configuration.category.CategoryOther;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class LagMonitorConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("nicos_super_mods")
            .resolve("nsm-lag-monitor.json");

    public boolean enabled = true;
    public boolean showHud = true;
    public boolean showTitles = true;
    public boolean showEndReport = true;
    public boolean copyTpsLossToClipboard = true;
    public boolean debugLogging = true;

    public boolean showInDungeons = true;
    public boolean showOnHypixelOutsideDungeons = true;
    public boolean showOnOtherServers = false;

    public boolean showHudBackground = true;
    public int hudBackgroundOpacity = 69;
    public boolean showHudAccentBar = true;
    public boolean showHudHeader = true;
    public boolean showHudTps = true;
    public boolean showHudPing = true;
    public boolean showHudJitter = true;
    public boolean showHudPacketGap = true;
    public boolean showHudDiagnosis = true;
    public boolean hudTextShadow = true;

    public int warmupSeconds = 6;
    public int pingSampleIntervalTicks = 20;
    public int tpsSampleStaleSeconds = 6;
    public int tcpPingSampleIntervalSeconds = 10;
    public int tcpPingTimeoutMillis = 2000;

    public double serverWarningTps = 15.0D;
    public double serverCriticalTps = 10.0D;
    public int highPingWarningMillis = 250;
    public int highPingCriticalMillis = 500;
    public double highJitterWarningMillis = 90.0D;
    public int stallWarningMillis = 1500;

    public int pingLossBaselineMillis = 100;
    public double pingSensitiveActionsPerSecond = 0.5D;

    public int tpsTitleDelayMillis = 2000;
    public int pingTitleDelayMillis = 2000;
    public int stallTitleDelayMillis = 500;
    public int titleCooldownMillis = 10_000;

    public int lowFpsThreshold = 30;
    public int hudXOffset = 8;
    public int hudYOffset = 8;

    public static LagMonitorConfig load() {
        if (!Files.exists(PATH)) {
            LagMonitorConfig config = new LagMonitorConfig();
            config.save();
            return config;
        }

        try (Reader reader = Files.newBufferedReader(PATH)) {
            JsonElement rawConfig = JsonParser.parseReader(reader);
            LagMonitorConfig loaded = GSON.fromJson(rawConfig, LagMonitorConfig.class);
            if (loaded == null) {
                loaded = new LagMonitorConfig();
            }
            loaded.migrateLegacyVisibility(rawConfig);
            loaded.sanitize();
            return loaded;
        } catch (IOException | RuntimeException e) {
            System.err.println("[NSM Lag] Could not load config: " + e.getMessage());
            return new LagMonitorConfig();
        }
    }

    public void save() {
        sanitize();
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            System.err.println("[NSM Lag] Could not save config: " + e.getMessage());
        }
    }

    public void sanitize() {
        hudBackgroundOpacity = clamp(hudBackgroundOpacity, 0, 100);
        warmupSeconds = clamp(warmupSeconds, 0, 30);
        pingSampleIntervalTicks = clamp(pingSampleIntervalTicks, 5, 200);
        tpsSampleStaleSeconds = clamp(tpsSampleStaleSeconds, 2, 30);
        tcpPingSampleIntervalSeconds = clamp(tcpPingSampleIntervalSeconds, 2, 60);
        tcpPingTimeoutMillis = clamp(tcpPingTimeoutMillis, 250, 10_000);
        serverWarningTps = clamp(serverWarningTps, 1.0D, 20.0D);
        serverCriticalTps = clamp(serverCriticalTps, 1.0D, serverWarningTps);
        highPingWarningMillis = clamp(highPingWarningMillis, 50, 5000);
        highPingCriticalMillis = clamp(highPingCriticalMillis, highPingWarningMillis, 10_000);
        highJitterWarningMillis = clamp(highJitterWarningMillis, 5.0D, 2000.0D);
        stallWarningMillis = clamp(stallWarningMillis, 500, 15_000);
        pingLossBaselineMillis = clamp(pingLossBaselineMillis, 0, 5000);
        pingSensitiveActionsPerSecond = clamp(pingSensitiveActionsPerSecond, 0.0D, 20.0D);
        tpsTitleDelayMillis = clamp(tpsTitleDelayMillis, 0, 30_000);
        pingTitleDelayMillis = clamp(pingTitleDelayMillis, 0, 30_000);
        stallTitleDelayMillis = clamp(stallTitleDelayMillis, 0, 30_000);
        titleCooldownMillis = clamp(titleCooldownMillis, 1000, 120_000);
        lowFpsThreshold = clamp(lowFpsThreshold, 5, 240);
        hudXOffset = clamp(hudXOffset, 0, 500);
        hudYOffset = clamp(hudYOffset, 0, 500);
    }

    public void applyMoulConfig(CategoryOther.LagMonitor settings) {
        if (settings == null) {
            return;
        }

        enabled = settings.enabled;
        showHud = settings.showHud;
        showTitles = settings.showTitles;
        showEndReport = settings.showEndReport;
        copyTpsLossToClipboard = settings.copyTpsLossToClipboard;

        if (settings.visibility != null) {
            showInDungeons = settings.visibility.showInDungeons;
            showOnHypixelOutsideDungeons =  settings.visibility.showOnHypixelOutsideDungeons;
            showOnOtherServers = settings.visibility.showOnOtherServers;
        }

        if (settings.design != null) {
            showHudBackground = settings.design.showBackground;
            hudBackgroundOpacity = settings.design.backgroundOpacity;
            showHudAccentBar = settings.design.showAccentBar;
            showHudHeader = settings.design.showHeader;
            showHudTps = settings.design.showTps;
            showHudPing = settings.design.showPing;
            showHudJitter = settings.design.showJitter;
            showHudPacketGap = settings.design.showPacketGap;
            showHudDiagnosis = settings.design.showDiagnosis;
            hudTextShadow = settings.design.textShadow;
        }
    }

    private void migrateLegacyVisibility(JsonElement rawConfig) {
        if (rawConfig == null || !rawConfig.isJsonObject()) return;

        JsonObject root = rawConfig.getAsJsonObject();
        JsonElement onlyDungeons = root.get("onlyShowInDungeons");
        if (onlyDungeons == null || !onlyDungeons.isJsonPrimitive() || !onlyDungeons.getAsJsonPrimitive().isBoolean()) return;

        showInDungeons = true;
        showOnHypixelOutsideDungeons = !onlyDungeons.getAsBoolean();
        showOnOtherServers = false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
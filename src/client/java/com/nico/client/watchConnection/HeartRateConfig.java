package com.nico.client.watchConnection;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class HeartRateConfig {
    private static final long TOKEN_EXPIRY_SAFETY_MARGIN_MS = 60_000L;

    public int x = 8;
    public int y = 8;
    public boolean enabled = true;

    public String accessToken = "";

    public long accessTokenExpiresAt = 0L;

    public static HeartRateConfig load() {
        HeartRateConfig config = new HeartRateConfig();
        Path path = getPath();
        Properties props = new Properties();

        try {
            if (Files.notExists(path)) {
                Files.createDirectories(path.getParent());
                config.save();
                return config;
            }

            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            }

            config.x = parseInt(props.getProperty("x"), 8);
            config.y = parseInt(props.getProperty("y"), 8);
            config.enabled = Boolean.parseBoolean(
                    props.getProperty("enabled", "true")
            );
            config.accessToken = props.getProperty("accessToken", "").trim();
            config.accessTokenExpiresAt = parseLong(
                    props.getProperty("accessTokenExpiresAt"),
                    0L
            );
        } catch (IOException exception) {
            System.err.println(
                    "[NSM HeartRate] Could not load config: "
                            + exception.getMessage()
            );
        }

        return config;
    }

    public synchronized void save() {
        Path path = getPath();
        Properties props = new Properties();

        props.setProperty("x", Integer.toString(x));
        props.setProperty("y", Integer.toString(y));
        props.setProperty("enabled", Boolean.toString(enabled));
        props.setProperty("accessToken", accessToken == null ? "" : accessToken);
        props.setProperty(
                "accessTokenExpiresAt",
                Long.toString(accessTokenExpiresAt)
        );

        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Heart Rate HUD config");
            }
        } catch (IOException exception) {
            System.err.println(
                    "[NSM HeartRate] Could not save config: "
                            + exception.getMessage()
            );
        }
    }

    public boolean hasUsableAccessToken() {
        if (accessToken == null || accessToken.isBlank()) {
            return false;
        }

        return accessTokenExpiresAt <= 0L
                || System.currentTimeMillis()
                < accessTokenExpiresAt - TOKEN_EXPIRY_SAFETY_MARGIN_MS;
    }

    public void setAccessToken(String token, long expiresInSeconds) {
        accessToken = token == null ? "" : token.trim();

        if (expiresInSeconds > 0L) {
            long now = System.currentTimeMillis();
            long durationMs;

            try {
                durationMs = Math.multiplyExact(expiresInSeconds, 1_000L);
                accessTokenExpiresAt = Math.addExact(now, durationMs);
            } catch (ArithmeticException exception) {
                accessTokenExpiresAt = Long.MAX_VALUE;
            }
        } else {
            accessTokenExpiresAt = 0L;
        }
    }

    public void clearAccessToken() {
        accessToken = "";
        accessTokenExpiresAt = 0L;
    }

    private static Path getPath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("nicos_super_mods")
                .resolve("heartratehud.properties");
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}

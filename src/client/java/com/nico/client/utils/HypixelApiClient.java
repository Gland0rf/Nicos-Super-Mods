package com.nico.client.utils;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.server.dialog.Input;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class HypixelApiClient {

    public static final String DEFAULT_BASE_URL = "https://api.hypixel.net/";

    private final String baseUrl;
    private final String apiKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public HypixelApiClient(String apiKey) {
        this(DEFAULT_BASE_URL, apiKey, 5000, 8000);
    }

    public HypixelApiClient(String baseUrl, String apiKey, int connectTimeoutMs, int readTimeoutMs) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = emptyToNull(apiKey);
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    public JsonObject getJson(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("path cannot be null or empty");
        }

        String cleanPath = path.startsWith("/") ? path : "/" + path;
        URL url = new URL(baseUrl + cleanPath);

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Nicos_Super_Mods");

        if (apiKey != null) {
            connection.setRequestProperty("API-Key", apiKey);
        }

        int statusCode = connection.getResponseCode();
        InputStream stream = statusCode >= 200 && statusCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();

        String body = readFully(stream);

        if (statusCode < 200 || statusCode >= 300) {
            throw new ApiException(statusCode, body);
        }

        try {
            return new JsonParser().parse(body).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Hypixel API returned invalid JSON", e);
        } finally {
            connection.disconnect();
        }
    }

    private static String readFully(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        StringBuilder result = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            String line;

            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }

        return result.toString();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_BASE_URL;
        }

        String trimmed = value.trim();

        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed;
    }

    private static String emptyToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }

        return value.trim();
    }

    public static final class ApiException extends IOException {
        private final int statusCode;
        private final String reponseBody;

        public ApiException(int statusCode, String reponseBody) {
            super("Hypixel API request failed with HTTP " + statusCode + ": " + reponseBody);
            this.statusCode = statusCode;
            this.reponseBody = reponseBody;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getReponseBody() {
            return reponseBody;
        }
    }

}

package com.nico.client.watchConnection;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class PulsoidOAuthClient {
    private static final URI DEVICE_AUTHORIZATION_URI =
            URI.create("https://pulsoid.net/oauth2/device_authorization");
    private static final URI TOKEN_URI =
            URI.create("https://pulsoid.net/oauth2/token");
    private static final String HEART_RATE_SCOPE = "data:heart_rate:read";
    private static final String DEVICE_GRANT_TYPE =
            "urn:ietf:params:oauth:grant-type:device_code";

    private final HttpClient httpClient;
    private final String clientId;
    private final AtomicBoolean authorizing = new AtomicBoolean(false);
    private final AtomicLong authorizationAttempt = new AtomicLong(0L);

    public PulsoidOAuthClient(String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public boolean isAuthorizing() {
        return authorizing.get();
    }

    public void cancelAuthorization() {
        authorizing.set(false);
        authorizationAttempt.incrementAndGet();
    }

    public void authorize(Listener listener) {
        if (clientId.isBlank()
                || clientId.equals("REPLACE_WITH_YOUR_PULSOID_CLIENT_ID")) {
            listener.onFailure(
                    "The developer has not configured a Pulsoid client ID.",
                    null
            );
            return;
        }

        if (!authorizing.compareAndSet(false, true)) {
            return;
        }

        long attempt = authorizationAttempt.incrementAndGet();
        listener.onStatus("Starting Pulsoid authorization...");

        HttpRequest request = formRequest(
                DEVICE_AUTHORIZATION_URI,
                "client_id", clientId,
                "scope", HEART_RATE_SCOPE
        );

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (!isActive(attempt)) {
                        return;
                    }

                    if (throwable != null) {
                        fail(
                                attempt,
                                listener,
                                "Could not start Pulsoid authorization.",
                                unwrap(throwable)
                        );
                        return;
                    }

                    try {
                        if (response.statusCode() < 200
                                || response.statusCode() >= 300) {
                            throw new IllegalStateException(
                                    "Device authorization returned HTTP "
                                            + response.statusCode()
                            );
                        }

                        JsonObject json = JsonParser.parseString(response.body())
                                .getAsJsonObject();

                        String deviceCode = requiredString(json, "device_code");
                        String verificationUri = requiredString(
                                json,
                                "verification_uri_complete"
                        );
                        long expiresIn = requiredLong(json, "expires_in");
                        int interval = (int) Math.max(
                                1L,
                                optionalLong(json, "interval", 5L)
                        );
                        long deadline = safeDeadline(expiresIn);

                        listener.onStatus(
                                "Authorize Pulsoid in your browser..."
                        );
                        listener.onVerificationUri(
                                URI.create(verificationUri)
                        );

                        pollForToken(
                                attempt,
                                deviceCode,
                                interval,
                                deadline,
                                listener
                        );
                    } catch (Exception exception) {
                        fail(
                                attempt,
                                listener,
                                "Pulsoid returned an invalid authorization response.",
                                exception
                        );
                    }
                });
    }

    private void pollForToken(
            long attempt,
            String deviceCode,
            int intervalSeconds,
            long deadline,
            Listener listener
    ) {
        if (!isActive(attempt)) {
            return;
        }

        if (System.currentTimeMillis() >= deadline) {
            fail(
                    attempt,
                    listener,
                    "Pulsoid authorization expired.",
                    null
            );
            return;
        }

        CompletableFuture.delayedExecutor(
                intervalSeconds,
                TimeUnit.SECONDS
        ).execute(() -> requestToken(
                attempt,
                deviceCode,
                intervalSeconds,
                deadline,
                listener
        ));
    }

    private void requestToken(
            long attempt,
            String deviceCode,
            int intervalSeconds,
            long deadline,
            Listener listener
    ) {
        if (!isActive(attempt)) {
            return;
        }

        HttpRequest request = formRequest(
                TOKEN_URI,
                "grant_type", DEVICE_GRANT_TYPE,
                "device_code", deviceCode,
                "client_id", clientId
        );

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (!isActive(attempt)) {
                        return;
                    }

                    if (throwable != null) {
                        fail(
                                attempt,
                                listener,
                                "Could not complete Pulsoid authorization.",
                                unwrap(throwable)
                        );
                        return;
                    }

                    try {
                        JsonObject json = JsonParser.parseString(response.body())
                                .getAsJsonObject();

                        if (response.statusCode() >= 200
                                && response.statusCode() < 300) {
                            String token = requiredString(json, "access_token");
                            long expiresIn = optionalLong(
                                    json,
                                    "expires_in",
                                    0L
                            );

                            if (!finish(attempt)) {
                                return;
                            }

                            listener.onAuthorized(
                                    new AccessToken(token, expiresIn)
                            );
                            return;
                        }

                        String error = optionalString(json, "error", "");
                        String description = optionalString(
                                json,
                                "error_description",
                                ""
                        );

                        switch (error) {
                            case "authorization_pending" -> pollForToken(
                                    attempt,
                                    deviceCode,
                                    intervalSeconds,
                                    deadline,
                                    listener
                            );
                            case "slow_down" -> pollForToken(
                                    attempt,
                                    deviceCode,
                                    intervalSeconds + 5,
                                    deadline,
                                    listener
                            );
                            case "expired_token" -> fail(
                                    attempt,
                                    listener,
                                    "Pulsoid authorization expired.",
                                    null
                            );
                            case "access_denied" -> fail(
                                    attempt,
                                    listener,
                                    "Pulsoid authorization was denied.",
                                    null
                            );
                            case "invalid_grant" -> fail(
                                    attempt,
                                    listener,
                                    description.isBlank()
                                            ? "Pulsoid authorization was not granted."
                                            : description,
                                    null
                            );
                            default -> fail(
                                    attempt,
                                    listener,
                                    "Pulsoid token request returned HTTP "
                                            + response.statusCode()
                                            + (error.isBlank()
                                            ? ""
                                            : " (" + error + ")"),
                                    null
                            );
                        }
                    } catch (Exception exception) {
                        fail(
                                attempt,
                                listener,
                                "Pulsoid returned an invalid token response.",
                                exception
                        );
                    }
                });
    }

    private boolean isActive(long attempt) {
        return authorizing.get()
                && authorizationAttempt.get() == attempt;
    }

    private boolean finish(long attempt) {
        if (!isActive(attempt)) {
            return false;
        }

        authorizing.set(false);
        return authorizationAttempt.get() == attempt;
    }

    private void fail(
            long attempt,
            Listener listener,
            String message,
            Throwable throwable
    ) {
        if (!finish(attempt)) {
            return;
        }

        listener.onFailure(message, throwable);
    }

    private static HttpRequest formRequest(
            URI uri,
            String... keyValues
    ) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "Form parameters must be key/value pairs."
            );
        }

        StringJoiner body = new StringJoiner("&");

        for (int index = 0; index < keyValues.length; index += 2) {
            body.add(encode(keyValues[index])
                    + "="
                    + encode(keyValues[index + 1]));
        }

        return HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(10))
                .header(
                        "Content-Type",
                        "application/x-www-form-urlencoded"
                )
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requiredString(JsonObject json, String name) {
        if (!json.has(name) || json.get(name).isJsonNull()) {
            throw new IllegalStateException("Missing field: " + name);
        }

        String value = json.get(name).getAsString();
        if (value.isBlank()) {
            throw new IllegalStateException("Blank field: " + name);
        }
        return value;
    }

    private static String optionalString(
            JsonObject json,
            String name,
            String fallback
    ) {
        return !json.has(name) || json.get(name).isJsonNull()
                ? fallback
                : json.get(name).getAsString();
    }

    private static long requiredLong(JsonObject json, String name) {
        if (!json.has(name) || json.get(name).isJsonNull()) {
            throw new IllegalStateException("Missing field: " + name);
        }
        return json.get(name).getAsLong();
    }

    private static long optionalLong(
            JsonObject json,
            String name,
            long fallback
    ) {
        return !json.has(name) || json.get(name).isJsonNull()
                ? fallback
                : json.get(name).getAsLong();
    }

    private static long safeDeadline(long expiresInSeconds) {
        try {
            return Math.addExact(
                    System.currentTimeMillis(),
                    Math.multiplyExact(expiresInSeconds, 1_000L)
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public record AccessToken(String value, long expiresInSeconds) {
    }

    public interface Listener {
        void onStatus(String message);

        void onVerificationUri(URI uri);

        void onAuthorized(AccessToken token);

        void onFailure(String message, Throwable throwable);
    }
}

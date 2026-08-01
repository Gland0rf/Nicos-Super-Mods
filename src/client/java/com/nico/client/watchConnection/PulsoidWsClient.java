package com.nico.client.watchConnection;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

public class PulsoidWsClient implements WebSocket.Listener {
    private static final URI WEBSOCKET_URI = URI.create(
            "wss://dev.pulsoid.net/api/v1/data/real_time"
    );
    private static final long INITIAL_RECONNECT_DELAY_MS = 2_000L;
    private static final long MAX_RECONNECT_DELAY_MS = 60_000L;

    private final HttpClient httpClient;
    private final HeartRateState state;
    private final Runnable unauthorizedHandler;
    private final StringBuilder partialMessage = new StringBuilder();

    private volatile String accessToken;
    private volatile WebSocket webSocket;
    private volatile boolean reconnectNeeded;
    private volatile boolean connecting;
    private volatile long nextReconnectAt;
    private volatile int reconnectAttempts;

    public PulsoidWsClient(
            String accessToken,
            HeartRateState state,
            Runnable unauthorizedHandler
    ) {
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.state = state;
        this.unauthorizedHandler = unauthorizedHandler;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void setAccessToken(String value) {
        accessToken = value == null ? "" : value.trim();
        reconnectNeeded = false;
        reconnectAttempts = 0;
        nextReconnectAt = 0L;

        WebSocket current = webSocket;
        if (current != null) {
            current.abort();
            webSocket = null;
        }
    }

    public void connect() {
        if (connecting) {
            return;
        }

        String token = accessToken;
        if (token.isBlank()) {
            state.setConnected(false);
            state.setStatus("Pulsoid authorization required");
            return;
        }

        connecting = true;
        reconnectNeeded = false;
        state.setConnected(false);
        state.setStatus("Connecting to Pulsoid...");

        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .buildAsync(WEBSOCKET_URI, this)
                .whenComplete((socket, throwable) -> {
                    connecting = false;

                    if (throwable == null) {
                        webSocket = socket;
                        return;
                    }

                    Throwable cause = unwrap(throwable);
                    state.setConnected(false);

                    if (isAuthorizationFailure(cause)) {
                        state.setStatus("Pulsoid authorization expired");
                        reconnectNeeded = false;
                        unauthorizedHandler.run();
                        return;
                    }

                    System.err.println(
                            "[NSM HeartRate] Connection failed: "
                                    + cause.getMessage()
                    );
                    state.setStatus("Pulsoid connection failed");
                    scheduleReconnect();
                });
    }

    public boolean shouldReconnect() {
        return reconnectNeeded
                && !connecting
                && !accessToken.isBlank()
                && System.currentTimeMillis() >= nextReconnectAt;
    }

    public void reconnect() {
        connect();
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.webSocket = webSocket;
        reconnectNeeded = false;
        reconnectAttempts = 0;
        nextReconnectAt = 0L;
        state.setConnected(true);
        state.setStatus("Connected to Pulsoid");

        System.out.println("[NSM HeartRate] Connected to Pulsoid.");
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(
            WebSocket webSocket,
            CharSequence data,
            boolean last
    ) {
        partialMessage.append(data);

        if (last) {
            parseMessage(partialMessage.toString());
            partialMessage.setLength(0);
        }

        webSocket.request(1);
        return null;
    }

    private void parseMessage(String message) {
        try {
            JsonObject root = JsonParser.parseString(message)
                    .getAsJsonObject();
            JsonObject data = root.getAsJsonObject("data");

            if (data != null && data.has("heart_rate")) {
                int bpm = data.get("heart_rate").getAsInt();
                state.setBpm(bpm);
                state.setConnected(true);
                state.setStatus("Connected to Pulsoid");
            }
        } catch (Exception exception) {
            System.err.println(
                    "[NSM HeartRate] Invalid Pulsoid message: "
                            + exception.getMessage()
            );
        }
    }

    @Override
    public CompletionStage<?> onClose(
            WebSocket webSocket,
            int statusCode,
            String reason
    ) {
        this.webSocket = null;
        state.setConnected(false);
        state.setStatus("Pulsoid disconnected");
        scheduleReconnect();

        System.out.println(
                "[NSM HeartRate] Disconnected: "
                        + statusCode + " " + reason
        );

        return WebSocket.Listener.super.onClose(
                webSocket,
                statusCode,
                reason
        );
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        this.webSocket = null;
        state.setConnected(false);
        state.setStatus("Pulsoid connection error");
        scheduleReconnect();

        System.err.println(
                "[NSM HeartRate] WebSocket error: "
                        + error.getMessage()
        );
    }

    private void scheduleReconnect() {
        long multiplier = 1L << Math.min(reconnectAttempts, 5);
        long delay = Math.min(
                MAX_RECONNECT_DELAY_MS,
                INITIAL_RECONNECT_DELAY_MS * multiplier
        );

        reconnectAttempts++;
        nextReconnectAt = System.currentTimeMillis() + delay;
        reconnectNeeded = true;
    }

    private static boolean isAuthorizationFailure(Throwable throwable) {
        return throwable instanceof WebSocketHandshakeException exception
                && (exception.getResponse().statusCode() == 401
                || exception.getResponse().statusCode() == 403);
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}

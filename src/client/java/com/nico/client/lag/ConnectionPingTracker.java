package com.nico.client.lag;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;

/**
 * Measures round-trip latency on the active Minecraft play connection.
 *
 * Unlike a raw TCP connect probe, this packet travels through the same
 * connection and proxy path as the dungeon traffic. The long token is echoed
 * by the server in ClientboundPongResponsePacket, allowing the client to
 * calculate the full round-trip time.
 */
final class ConnectionPingTracker {
    private static final long NO_PENDING_TOKEN = Long.MIN_VALUE;

    private long pendingToken = NO_PENDING_TOKEN;
    private long pendingSinceNanos;
    private long lastRequestNanos;
    private int latestMillis = -1;

    synchronized void reset() {
        pendingToken = NO_PENDING_TOKEN;
        pendingSinceNanos = 0L;
        lastRequestNanos = 0L;
        latestMillis = -1;
    }

    synchronized int latest() {
        return latestMillis;
    }

    synchronized boolean hasPendingRequest() {
        return pendingToken != NO_PENDING_TOKEN;
    }

    synchronized void tick(
            Minecraft client,
            int intervalSeconds,
            int timeoutMillis
    ) {
        long now = System.nanoTime();
        expireTimedOutRequest(now, timeoutMillis);

        if (pendingToken != NO_PENDING_TOKEN) {
            return;
        }

        long intervalNanos = Math.max(1, intervalSeconds) * 1_000_000_000L;
        if (lastRequestNanos != 0L && now - lastRequestNanos < intervalNanos) {
            return;
        }

        requestNow(client, timeoutMillis);
    }

    synchronized void requestNow(Minecraft client, int timeoutMillis) {
        long now = System.nanoTime();
        expireTimedOutRequest(now, timeoutMillis);

        if (pendingToken != NO_PENDING_TOKEN
                || client == null
                || client.getConnection() == null) {
            return;
        }

        // The server echoes this exact value in the pong packet.
        long token = now;
        pendingToken = token;
        pendingSinceNanos = now;
        lastRequestNanos = now;

        try {
            client.getConnection().send(new ServerboundPingRequestPacket(token));
        } catch (RuntimeException exception) {
            pendingToken = NO_PENDING_TOKEN;
            pendingSinceNanos = 0L;
            throw exception;
        }
    }

    /**
     * @return measured RTT in milliseconds, or -1 when this pong does not
     * belong to a request made by this tracker.
     */
    synchronized int accept(ClientboundPongResponsePacket packet) {
        if (packet == null
                || pendingToken == NO_PENDING_TOKEN
                || packet.time() != pendingToken) {
            return -1;
        }

        long elapsedNanos = Math.max(0L, System.nanoTime() - pendingSinceNanos);
        int elapsedMillis = (int) Math.max(
                1L,
                Math.min(60_000L, Math.round(elapsedNanos / 1_000_000.0D))
        );

        latestMillis = elapsedMillis;
        pendingToken = NO_PENDING_TOKEN;
        pendingSinceNanos = 0L;
        return elapsedMillis;
    }

    private void expireTimedOutRequest(long nowNanos, int timeoutMillis) {
        if (pendingToken == NO_PENDING_TOKEN) {
            return;
        }

        long timeoutNanos = Math.max(250, timeoutMillis) * 1_000_000L;
        if (nowNanos - pendingSinceNanos >= timeoutNanos) {
            pendingToken = NO_PENDING_TOKEN;
            pendingSinceNanos = 0L;
        }
    }
}
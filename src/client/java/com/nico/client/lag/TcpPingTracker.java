package com.nico.client.lag;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

final class TcpPingTracker {
    private static final int DEFAULT_PORT = 25565;

    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final ExecutorService executor;

    private volatile int latestMillis = -1;
    private volatile long lastProbeNanos;

    TcpPingTracker() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "NSM Lag TCP Ping");
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newSingleThreadExecutor(factory);
    }

    void reset() {
        latestMillis = -1;
        lastProbeNanos = 0L;
    }

    int latest() {
        return latestMillis;
    }

    void tick(
            Minecraft client,
            int intervalSeconds,
            int timeoutMillis,
            IntConsumer callback
    ) {
        long now = System.nanoTime();
        long intervalNanos = Math.max(1, intervalSeconds) * 1_000_000_000L;
        if (now - lastProbeNanos < intervalNanos) {
            return;
        }
        requestNow(client, timeoutMillis, callback);
    }

    void requestNow(Minecraft client, int timeoutMillis, IntConsumer callback) {
        ServerData server = client == null ? null : client.getCurrentServer();
        if (server == null || server.ip == null || server.ip.isBlank()) {
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            return;
        }

        HostAndPort target = parse(server.ip);
        lastProbeNanos = System.nanoTime();
        int safeTimeout = Math.max(250, Math.min(10_000, timeoutMillis));

        executor.execute(() -> {
            int result = -1;
            try (Socket socket = new Socket()) {
                long started = System.nanoTime();
                socket.connect(
                        new InetSocketAddress(target.host(), target.port()),
                        safeTimeout
                );
                long elapsedNanos = System.nanoTime() - started;
                result = (int) Math.max(
                        1L,
                        Math.min(60_000L, Math.round(elapsedNanos / 1_000_000.0D))
                );
                latestMillis = result;
            } catch (Exception ignored) {

            } finally {
                inFlight.set(false);
                if (result >= 0 && callback != null) {
                    callback.accept(result);
                }
            }
        });
    }

    private static HostAndPort parse(String rawAddress) {
        String address = rawAddress.trim();
        String host = address;
        int port = DEFAULT_PORT;

        if (address.startsWith("[")) {
            int closingBracket = address.indexOf(']');
            if (closingBracket > 0) {
                host = address.substring(1, closingBracket);
                if (closingBracket + 1 < address.length()
                        && address.charAt(closingBracket + 1) == ':') {
                    port = parsePort(address.substring(closingBracket + 2));
                }
            }
        } else {
            int firstColon = address.indexOf(':');
            int lastColon = address.lastIndexOf(':');
            if (firstColon > 0 && firstColon == lastColon) {
                host = address.substring(0, firstColon);
                port = parsePort(address.substring(firstColon + 1));
            }
        }

        return new HostAndPort(host, port);
    }

    private static int parsePort(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 1 && parsed <= 65_535 ? parsed : DEFAULT_PORT;
        } catch (NumberFormatException ignored) {
            return DEFAULT_PORT;
        }
    }

    private record HostAndPort(String host, int port) {
    }
}
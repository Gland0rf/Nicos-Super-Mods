package com.nico.client.lag;

import java.util.ArrayDeque;

/**
 * Estimates server TPS from Hypixel's per-server-tick ClientboundPingPacket.
 *
 * Hypixel sends a non-zero common ping packet once for each server tick. Using
 * a multi-second timestamp window makes the estimate resistant to normal
 * network jitter and packet batching.
 */
public final class TpsEstimator {
    private static final long WINDOW_NANOS = 5_000_000_000L;
    private static final int MAX_WINDOW_SAMPLES = 160;

    private final ArrayDeque<Long> tickTimes = new ArrayDeque<>();

    private long lastServerTickNanos;
    private long totalServerTicks;
    private double smoothedTps = Double.NaN;

    synchronized void reset() {
        tickTimes.clear();
        lastServerTickNanos = 0L;
        totalServerTicks = 0L;
        smoothedTps = Double.NaN;
    }

    synchronized void onServerTick(long nowNanos) {
        if (nowNanos <= 0L) {
            return;
        }

        // Prevent duplicate processing if another hook forwards the same packet.
        if (lastServerTickNanos != 0L && nowNanos <= lastServerTickNanos) {
            return;
        }

        lastServerTickNanos = nowNanos;
        totalServerTicks++;
        tickTimes.addLast(nowNanos);

        long cutoff = nowNanos - WINDOW_NANOS;
        while (tickTimes.size() > 2 && tickTimes.getFirst() < cutoff) {
            tickTimes.removeFirst();
        }
        while (tickTimes.size() > MAX_WINDOW_SAMPLES) {
            tickTimes.removeFirst();
        }

        if (tickTimes.size() < 2) {
            return;
        }

        long first = tickTimes.getFirst();
        long last = tickTimes.getLast();
        long elapsedNanos = last - first;
        int tickIntervals = tickTimes.size() - 1;

        if (elapsedNanos <= 0L || tickIntervals <= 0) {
            return;
        }

        double rawTps = tickIntervals * 1_000_000_000.0D / elapsedNanos;
        rawTps = Math.max(0.0D, Math.min(20.0D, rawTps));

        if (!Double.isFinite(rawTps) || rawTps < 0.5D) {
            return;
        }

        // The window already smooths heavily. This small EMA removes remaining
        // display flicker without hiding a sustained TPS drop.
        smoothedTps = Double.isFinite(smoothedTps)
                ? smoothedTps * 0.70D + rawTps * 0.30D
                : rawTps;
    }

    synchronized double currentTps(long nowNanos, int staleSeconds) {
        if (!Double.isFinite(smoothedTps) || lastServerTickNanos <= 0L) {
            return Double.NaN;
        }

        long staleNanos = Math.max(1, staleSeconds) * 1_000_000_000L;
        if (nowNanos - lastServerTickNanos > staleNanos) {
            return Double.NaN;
        }

        return smoothedTps;
    }

    synchronized long totalServerTicks() {
        return totalServerTicks;
    }

    synchronized int windowSampleCount() {
        return tickTimes.size();
    }
}
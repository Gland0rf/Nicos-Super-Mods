package com.nico.client.lag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TpsEstimator {
    private static final int SAMPLE_WINDOW = 9;

    private final ArrayDeque<Double> samples = new ArrayDeque<>();
    private long lastGameTime = Long.MIN_VALUE;
    private long lastPacketNanos;
    private double smoothedTps = Double.NaN;

    synchronized void reset() {
        samples.clear();
        lastGameTime = Long.MIN_VALUE;
        lastPacketNanos = 0L;
        smoothedTps = Double.NaN;
    }

    synchronized void onTimePacket(long gameTime, long nowNanos) {
        if (lastGameTime != Long.MIN_VALUE && lastPacketNanos > 0L) {
            long tickDelta = gameTime - lastGameTime;
            double seconds = (nowNanos - lastPacketNanos) / 1_000_000_000.0D;

            if (tickDelta > 0L && tickDelta <= 400L && seconds >= 0.20D && seconds <= 12.0D) {
                double rawTps = Math.min(20.0D, tickDelta / seconds);
                if (rawTps >= 0.5D && Double.isFinite(rawTps)) {
                    samples.addLast(rawTps);
                    while (samples.size() > SAMPLE_WINDOW) {
                        samples.removeFirst();
                    }

                    double median = median(samples);
                    smoothedTps = Double.isFinite(smoothedTps)
                            ? smoothedTps * 0.72D + median * 0.28D
                            : median;
                }
            }
        }

        lastGameTime = gameTime;
        lastPacketNanos = nowNanos;
    }

    synchronized double currentTps(long nowNanos, int staleSeconds) {
        if (!Double.isFinite(smoothedTps) || lastPacketNanos <= 0L) {
            return Double.NaN;
        }
        if (nowNanos - lastPacketNanos > staleSeconds * 1_000_000_000L) {
            return Double.NaN;
        }
        return smoothedTps;
    }

    private static double median(ArrayDeque<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if ((sorted.size() & 1) == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2.0D;
    }
}

package com.nico.client.lag;

import java.util.ArrayDeque;

final class PingTracker {
    private static final int SAMPLE_WINDOW = 30;
    private final ArrayDeque<Integer> samples = new ArrayDeque<>();

    synchronized void reset() {
        samples.clear();
    }

    synchronized void addSample(int pingMillis) {
        if (pingMillis < 0 || pingMillis > 60_000) {
            return;
        }
        samples.addLast(pingMillis);
        while (samples.size() > SAMPLE_WINDOW) {
            samples.removeFirst();
        }
    }

    synchronized int latest() {
        return samples.isEmpty() ? -1 : samples.getLast();
    }

    synchronized double average() {
        if (samples.isEmpty()) {
            return Double.NaN;
        }
        long total = 0L;
        for (int sample : samples) {
            total += sample;
        }
        return total / (double) samples.size();
    }

    synchronized double jitter() {
        if (samples.size() < 2) {
            return Double.NaN;
        }

        Integer previous = null;
        long totalDifference = 0L;
        int differences = 0;
        for (int sample : samples) {
            if (previous != null) {
                totalDifference += Math.abs(sample - previous);
                differences++;
            }
            previous = sample;
        }
        return differences == 0 ? Double.NaN : totalDifference / (double) differences;
    }
}

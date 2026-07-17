package com.nico.client.lag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LagSessionStats {
    private final long startedNanos;
    private final List<Integer> pingSamples = new ArrayList<>();

    private double totalTrackedSeconds;
    private double weightedTpsTotal;
    private double tpsWeightSeconds;
    private double lowestTps = Double.POSITIVE_INFINITY;
    private double secondsBelow15;
    private double secondsBelow10;
    private double estimatedServerDelaySeconds;
    private double networkStallSeconds;

    private long pingTotal;
    private int pingCount;
    private int highestPing = -1;
    private double jitterTotal;
    private int jitterCount;

    private int tpsDropCount;
    private int pingSpikeCount;
    private int stallCount;
    private double currentStallSeconds;
    private double longestStallSeconds;

    private boolean wasLowTps;
    private boolean wasHighPing;
    private boolean wasStalled;

    LagSessionStats(long startedNanos) {
        this.startedNanos = startedNanos;
    }

    void recordPingSample(int pingMillis, double jitterMillis) {
        if (pingMillis >= 0) {
            pingSamples.add(pingMillis);
            pingTotal += pingMillis;
            pingCount++;
            highestPing = Math.max(highestPing, pingMillis);
        }
        if (Double.isFinite(jitterMillis)) {
            jitterTotal += jitterMillis;
            jitterCount++;
        }
    }

    void accept(LagSnapshot snapshot, double intervalSeconds, LagMonitorConfig config) {
        if (!snapshot.active() || snapshot.warmingUp()) return;

        double dt = Math.max(0.0D, Math.min(0.25D, intervalSeconds));
        totalTrackedSeconds += dt;

        if (snapshot.hasTpsEstimate()) {
            double tps = snapshot.estimatedTps();
            weightedTpsTotal += tps * dt;
            tpsWeightSeconds += dt;
            lowestTps = Math.min(lowestTps, tps);

            if (tps < 15.0D) {
                secondsBelow15 += dt;
            }
            if (tps < 10.0D) {
                secondsBelow10 += dt;
            }

            estimatedServerDelaySeconds += dt * Math.max(0.0D, 1.0D - tps / 20.0D);
        }

        boolean lowTps = snapshot.hasTpsEstimate() && snapshot.estimatedTps() < config.serverWarningTps;
        boolean highPing = snapshot.hasPing() && snapshot.pingMillis() >= config.highPingWarningMillis;
        boolean stalled = snapshot.packetGapMillis() >= config.stallWarningMillis;

        if (lowTps && !wasLowTps) {
            tpsDropCount++;
        }
        if (highPing && !wasHighPing) {
            pingSpikeCount++;
        }
        if (stalled && !wasStalled) {
            stallCount++;
            currentStallSeconds = 0.0D;
        }

        if (stalled) {
            networkStallSeconds += dt;
            currentStallSeconds += dt;
            longestStallSeconds = Math.max(longestStallSeconds, currentStallSeconds);
        } else {
            currentStallSeconds = 0.0D;
        }

        wasLowTps = lowTps;
        wasHighPing = highPing;
        wasStalled = stalled;
    }

    double estimatedServerDelaySeconds() {
        return estimatedServerDelaySeconds;
    }

    double networkStallSeconds() {
        return networkStallSeconds;
    }

    LagSessionSummary finish(long nowNanos) {
        double sessionSeconds = Math.max(totalTrackedSeconds, (nowNanos - startedNanos) / 1_000_000_000.0D);
        double averageTps = tpsWeightSeconds > 0.0D ? weightedTpsTotal / tpsWeightSeconds : Double.NaN;
        double minTps = lowestTps == Double.POSITIVE_INFINITY ? Double.NaN : lowestTps;
        double averagePing = pingCount > 0 ? pingTotal / (double) pingCount : Double.NaN;
        double averageJitter = jitterCount > 0 ? jitterTotal / jitterCount : Double.NaN;

        int p95 = percentile95(pingSamples);

        return new LagSessionSummary(
                sessionSeconds,
                estimatedServerDelaySeconds,
                networkStallSeconds,
                averageTps,
                minTps,
                secondsBelow15,
                secondsBelow10,
                averagePing,
                p95,
                highestPing,
                averageJitter,
                tpsDropCount,
                pingSpikeCount,
                stallCount,
                longestStallSeconds
        );
    }

    private static int percentile95(List<Integer> values) {
        if (values.isEmpty()) {
            return -1;
        }
        List<Integer> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * 0.95D) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }
}

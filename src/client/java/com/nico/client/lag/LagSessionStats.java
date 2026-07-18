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
    private double estimatedPingDelaySeconds;
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

    // Tab timer fallback. Hypixel does not always provide usable time-update
    // packets, so derive effective TPS from server timer progress vs wall time.
    private int firstTimerSeconds = -1;
    private int lastTimerSeconds = -1;
    private long firstTimerNanos;
    private long lastTimerChangeNanos;
    private double timerWeightedTpsTotal;
    private double timerTpsWeightSeconds;
    private double timerLowestTps = Double.POSITIVE_INFINITY;
    private double timerSecondsBelow15;
    private double timerSecondsBelow10;
    private int timerDropCount;
    private boolean timerWasLow;

    LagSessionStats(long startedNanos) {
        this.startedNanos = startedNanos;
    }

    void recordPingSample(int pingMillis, double jitterMillis) {
        if (pingMillis >= 0 && pingMillis <= 60_000) {
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

    void recordDungeonTimer(int elapsedSeconds, long nowNanos) {
        if (elapsedSeconds < 0) {
            return;
        }

        if (firstTimerSeconds < 0 || elapsedSeconds + 2 < lastTimerSeconds) {
            firstTimerSeconds = elapsedSeconds;
            lastTimerSeconds = elapsedSeconds;
            firstTimerNanos = nowNanos;
            lastTimerChangeNanos = nowNanos;
            timerWasLow = false;
            return;
        }

        if (elapsedSeconds <= lastTimerSeconds) {
            return;
        }

        int serverDeltaSeconds = elapsedSeconds - lastTimerSeconds;
        double wallDeltaSeconds = (nowNanos - lastTimerChangeNanos) / 1_000_000_000.0D;

        if (wallDeltaSeconds >= 0.20D && wallDeltaSeconds <= 15.0D) {
            double sampleTps = Math.max(
                    0.0D,
                    Math.min(20.0D, 20.0D * serverDeltaSeconds / wallDeltaSeconds)
            );

            timerWeightedTpsTotal += sampleTps * wallDeltaSeconds;
            timerTpsWeightSeconds += wallDeltaSeconds;
            timerLowestTps = Math.min(timerLowestTps, sampleTps);

            if (sampleTps < 15.0D) {
                timerSecondsBelow15 += wallDeltaSeconds;
            }
            if (sampleTps < 10.0D) {
                timerSecondsBelow10 += wallDeltaSeconds;
            }

            boolean timerLow = sampleTps < 15.0D;
            if (timerLow && !timerWasLow) {
                timerDropCount++;
            }
            timerWasLow = timerLow;
        }

        lastTimerSeconds = elapsedSeconds;
        lastTimerChangeNanos = nowNanos;
    }

    void accept(LagSnapshot snapshot, double intervalSeconds, LagMonitorConfig config) {
        if (!snapshot.active()) {
            return;
        }

        // Do not use the connection warm-up flag here. The dungeon timer itself
        // defines the beginning of this session.
        double dt = Math.max(0.0D, Math.min(0.25D, intervalSeconds));
        totalTrackedSeconds += dt;

        boolean stalled = snapshot.packetGapMillis() >= config.stallWarningMillis;
        boolean lowTps = snapshot.hasTpsEstimate()
                && snapshot.estimatedTps() < config.serverWarningTps;
        boolean highPing = snapshot.hasPing()
                && snapshot.pingMillis() >= config.highPingWarningMillis;

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

            if (!stalled) {
                estimatedServerDelaySeconds += dt * Math.max(0.0D, 1.0D - tps / 20.0D);
            }
        }

        if (snapshot.hasPing() && !stalled) {
            double excessRoundTripSeconds = Math.max(
                    0.0D,
                    snapshot.pingMillis() - config.pingLossBaselineMillis
            ) / 1000.0D;

            estimatedPingDelaySeconds += dt
                    * excessRoundTripSeconds
                    * config.pingSensitiveActionsPerSecond;
        }

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

    int pingSampleCount() {
        return pingCount;
    }

    double packetTpsSampleSeconds() {
        return tpsWeightSeconds;
    }

    double timerTpsSampleSeconds() {
        if (timerTpsWeightSeconds > 0.0D) {
            return timerTpsWeightSeconds;
        }
        return firstTimerNanos > 0L && lastTimerChangeNanos > firstTimerNanos
                ? (lastTimerChangeNanos - firstTimerNanos) / 1_000_000_000.0D
                : 0.0D;
    }

    LagSessionSummary finish(long nowNanos, LagMonitorConfig config) {
        double sessionSeconds = Math.max(
                totalTrackedSeconds,
                (nowNanos - startedNanos) / 1_000_000_000.0D
        );

        boolean usePacketTps = tpsWeightSeconds >= 5.0D;
        double timerWallSpan = firstTimerNanos > 0L && lastTimerChangeNanos > firstTimerNanos
                ? (lastTimerChangeNanos - firstTimerNanos) / 1_000_000_000.0D
                : 0.0D;
        double timerServerSpan = firstTimerSeconds >= 0 && lastTimerSeconds > firstTimerSeconds
                ? lastTimerSeconds - firstTimerSeconds
                : 0.0D;
        boolean hasTimerSpan = timerWallSpan >= 0.20D && timerServerSpan > 0.0D;
        boolean hasTimerTps = timerTpsWeightSeconds > 0.0D || hasTimerSpan;

        // Prefer the per-change weighted result, but a first/last timer span is
        // enough to provide an effective TPS even if some intermediate tab
        // packets were coalesced or missed.
        double timerAverageTps = timerTpsWeightSeconds > 0.0D
                ? timerWeightedTpsTotal / timerTpsWeightSeconds
                : hasTimerSpan
                ? Math.max(0.0D, Math.min(20.0D, 20.0D * timerServerSpan / timerWallSpan))
                : Double.NaN;
        double packetAverageTps = tpsWeightSeconds > 0.0D
                ? weightedTpsTotal / tpsWeightSeconds
                : Double.NaN;

        double averageTps = usePacketTps
                ? packetAverageTps
                : timerAverageTps;
        if (!Double.isFinite(averageTps)) {
            averageTps = packetAverageTps;
        }

        double minTps = usePacketTps
                ? (lowestTps == Double.POSITIVE_INFINITY ? Double.NaN : lowestTps)
                : (timerLowestTps == Double.POSITIVE_INFINITY ? Double.NaN : timerLowestTps);
        if (!Double.isFinite(minTps) && hasTimerSpan && !usePacketTps) {
            minTps = timerAverageTps;
        }
        if (!Double.isFinite(minTps) && lowestTps != Double.POSITIVE_INFINITY) {
            minTps = lowestTps;
        }

        double finalServerDelay = estimatedServerDelaySeconds;
        double finalBelow15 = secondsBelow15;
        double finalBelow10 = secondsBelow10;
        int finalDropCount = tpsDropCount;

        if (!usePacketTps && hasTimerSpan) {
            // Allow for the one-second display quantization before calling it lag.
            finalServerDelay = Math.max(0.0D, timerWallSpan - timerServerSpan - 0.75D);
            finalBelow15 = timerSecondsBelow15;
            finalBelow10 = timerSecondsBelow10;
            finalDropCount = timerDropCount;
        }

        double averagePing = pingCount > 0
                ? pingTotal / (double) pingCount
                : Double.NaN;
        double averageJitter = jitterCount > 0
                ? jitterTotal / jitterCount
                : Double.NaN;

        // A ping sample can arrive late in a run. Estimate the whole-run impact
        // from the observed average so the result does not stay at 0.0 merely
        // because there were few tick intervals after the first pong.
        double finalPingDelay = estimatedPingDelaySeconds;
        if (Double.isFinite(averagePing) && config != null) {
            double excessRoundTripSeconds = Math.max(
                    0.0D,
                    averagePing - config.pingLossBaselineMillis
            ) / 1000.0D;
            double wholeRunEstimate = sessionSeconds
                    * excessRoundTripSeconds
                    * config.pingSensitiveActionsPerSecond;
            finalPingDelay = Math.max(finalPingDelay, wholeRunEstimate);
        }

        return new LagSessionSummary(
                sessionSeconds,
                finalServerDelay,
                finalPingDelay,
                networkStallSeconds,
                averageTps,
                minTps,
                finalBelow15,
                finalBelow10,
                averagePing,
                percentile95(pingSamples),
                highestPing,
                averageJitter,
                finalDropCount,
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
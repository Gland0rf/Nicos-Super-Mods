package com.nico.client.memleak;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record AnalysisReport(
        State state,
        String explanation,
        Instant windowStart,
        Instant windowEnd,
        int postGcSamples,
        Duration observedFor,
        long firstPostGcHeapBytes,
        long latestPostGcHeapBytes,
        long maximumHeapBytes,
        double growthRateBytesPerMinute,
        double rSquared,
        double monotonicity,
        List<Candidate> candidates
) {
    public enum State {
        WARMING_UP,
        STABLE,
        SUSPICIOUS
    }

    public record Candidate(ModIdentity mod, long sampledAllocationBytes, double allocationShare) {
    }

    public AnalysisReport {
        candidates = List.copyOf(candidates);
    }

    public long observedGrowthBytes() {
        return latestPostGcHeapBytes - firstPostGcHeapBytes;
    }
}

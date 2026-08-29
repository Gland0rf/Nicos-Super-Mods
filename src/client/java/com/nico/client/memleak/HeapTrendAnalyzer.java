package com.nico.client.memleak;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class HeapTrendAnalyzer {
    private final MemLeakConfig config;
    private final ModClassIndex classIndex;
    private final Deque<HeapSnapshot> samples = new ArrayDeque<>();

    public HeapTrendAnalyzer(MemLeakConfig config, ModClassIndex classIndex) {
        this.config = config;
        this.classIndex = classIndex;
    }

    public synchronized void add(HeapSnapshot snapshot) {
        samples.addLast(snapshot);
        Instant cutOff = snapshot.time().minus(config.window());
        while (!samples.isEmpty() && samples.getFirst().time().isBefore(cutOff)) {
            samples.removeFirst();
        }
    }

    public synchronized void reset() {
        samples.clear();
    }

    public synchronized List<HeapSnapshot> history() {
        return List.copyOf(samples);
    }

    public synchronized AnalysisReport analyze(long maximumHeapBytes) {
        if (samples.isEmpty()) {
            Instant now = Instant.now();
            return new AnalysisReport(
                    AnalysisReport.State.WARMING_UP,
                    "Waiting for the first completed major garbage collection.",
                    now, now, 0, Duration.ZERO, 0, 0, maximumHeapBytes,
                    0, 0, 0, List.of()
            );
        }

        List<HeapSnapshot> points = List.copyOf(samples);
        HeapSnapshot first = points.getFirst();
        HeapSnapshot last = points.getLast();
        Duration observed = Duration.between(first.time(), last.time());
        Regression regression = regression(points);
        double monotonicity = monotonicity(points);
        List<AnalysisReport.Candidate> candidates = candidates(first, last);

        if (points.size() < config.minimumSamples() || observed.compareTo(config.minimumObservation()) < 0) {
            return new AnalysisReport(
                    AnalysisReport.State.WARMING_UP,
                    "Collecting a longer post-GC baseline before judging the trend.",
                    first.time(), last.time(), points.size(), observed,
                    first.postGcHeapBytes(), last.postGcHeapBytes(), maximumHeapBytes,
                    regression.slopeBytesPerMinute, regression.rSquared, monotonicity, candidates
            );
        }

        long requiredGrowth = Math.max(config.minimumGrowthBytes(), Math.round(maximumHeapBytes * 0.03));
        long observedGrowth = last.postGcHeapBytes() - first.postGcHeapBytes();
        boolean suspicious = observedGrowth >= requiredGrowth
                && regression.slopeBytesPerMinute >= config.minimumGrowthRateBytesPerMinute()
                && regression.rSquared >= config.minimumRSquared()
                && monotonicity >= config.minimumMonotonicity();

        String explanation = suspicious
                ? "The memory floor after GC is rising persistently; sampled allocators are candidates, not proof of retention."
                : "The current post-GC samples do not meet the sustained-growth thresholds.";

        return new AnalysisReport(
                suspicious ? AnalysisReport.State.SUSPICIOUS : AnalysisReport.State.STABLE,
                explanation,
                first.time(), last.time(), points.size(), observed,
                first.postGcHeapBytes(), last.postGcHeapBytes(), maximumHeapBytes,
                regression.slopeBytesPerMinute, regression.rSquared, monotonicity, candidates
        );
    }

    private Regression regression(List<HeapSnapshot> points) {
        if (points.size() < 2) {
            return new Regression(0, 0);
        }
        Instant origin = points.getFirst().time();
        double meanX = 0;
        double meanY = 0;
        for (HeapSnapshot point : points) {
            meanX += Duration.between(origin, point.time()).toMillis() / 60_000.0;
            meanY += point.postGcHeapBytes();
        }
        meanX /= points.size();
        meanY /= points.size();

        double covariance = 0;
        double varianceX = 0;
        double varianceY = 0;
        for (HeapSnapshot point : points) {
            double x = Duration.between(origin, point.time()).toMillis() / 60_000.0;
            double dx = x - meanX;
            double dy = point.postGcHeapBytes() - meanY;
            covariance += dx * dy;
            varianceX += dx * dx;
            varianceY += dy * dy;
        }
        if (varianceX == 0) {
            return new Regression(0, 0);
        }
        double slope = covariance / varianceX;
        double rSquared = varianceY == 0 ? 0 : (covariance * covariance) / (varianceX * varianceY);
        return new Regression(slope, Math.clamp(rSquared, 0, 1));
    }

    private double monotonicity(List<HeapSnapshot> points) {
        if (points.size() < 2) {
            return 0;
        }
        int nonDecreasing = 0;
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).postGcHeapBytes() >= points.get(i - 1).postGcHeapBytes()) {
                nonDecreasing++;
            }
        }
        return nonDecreasing / (double) (points.size() - 1);
    }

    private List<AnalysisReport.Candidate> candidates(HeapSnapshot first, HeapSnapshot last) {
        Map<String, Long> deltas = new HashMap<>();
        last.cumulativeSampledAllocationBytes().forEach((id, value) -> {
            long start = first.cumulativeSampledAllocationBytes().getOrDefault(id, 0L);
            long delta = Math.max(0, value - start);
            if (delta > 0) {
                deltas.put(id, delta);
            }
        });

        long total = deltas.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) {
            return List.of();
        }

        List<AnalysisReport.Candidate> result = new ArrayList<>();
        deltas.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(config.topCandidates())
                .forEach(entry -> classIndex.mod(entry.getKey()).ifPresent(mod ->
                        result.add(new AnalysisReport.Candidate(mod, entry.getValue(), entry.getValue() / (double) total))));
        return List.copyOf(result);
    }

    private record Regression(double slopeBytesPerMinute, double rSquared) {
    }
}

package com.nico.client.memleak;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class ThreadLeakDetector {
    public record OwnerGrowth(ModIdentity mod, int firstCount, int latestCount, int growth) { }

    public record Report(
            boolean suspicious,
            int samples,
            Duration observedFor,
            int firstThreadCount,
            int latestThreadCount,
            List<OwnerGrowth> ownerGrowth
    ) {
        public Report {
            ownerGrowth = List.copyOf(ownerGrowth);
        }

        public int totalGrowth() {
            return latestThreadCount - firstThreadCount;
        }
    }

    private record Sample(Instant time, int totalThreads, Map<String, Integer> threadsByMod) {
        private Sample {
            threadsByMod = Map.copyOf(threadsByMod);
        }
    }

    private static final int MINIMUM_SAMPLES = 4;
    private static final Duration MINIMUM_OBSERVATION = Duration.ofMinutes(5);
    private static final int SUSPICIOUS_TOTAL_GROWTH = 8;
    private static final int SUSPICIOUS_MOD_GROWTH = 4;

    private final Duration window;
    private final int maximumStackFrames;
    private final Deque<Sample> samples = new ArrayDeque<>();

    public ThreadLeakDetector(Duration window, int maximumStackFrames) {
        this.window = window;
        this.maximumStackFrames = maximumStackFrames;
    }

    public void sample(Instant time, ModClassIndex classIndex) {
        Map<Thread, StackTraceElement[]> liveThreads = Thread.getAllStackTraces();
        Map<String, Integer> byMod = new HashMap<>();
        int total = 0;

        for (Map.Entry<Thread, StackTraceElement[]> entry : liveThreads.entrySet()) {
            if (!entry.getKey().isAlive()) continue;
            total++;

            int inspected = 0;
            for (StackTraceElement frame : entry.getValue()) {
                if (inspected++ >= maximumStackFrames) break;
                var owner = classIndex.ownerOf(frame.getClassName());
                if (owner.isPresent() && !isInfrastructureMod(owner.get().id())) {
                    byMod.merge(owner.get().id(), 1, Integer::sum);
                    break;
                }
            }
        }

        synchronized (this) {
            samples.addLast(new Sample(time, total, byMod));
            Instant cutoff = time.minus(window);
            while (!samples.isEmpty() && samples.getFirst().time().isBefore(cutoff)) {
                samples.removeFirst();
            }
        }
    }

    public synchronized Report analyze(ModClassIndex classIndex) {
        if (samples.isEmpty()) {
            return new Report(false, 0, Duration.ZERO, 0, 0, List.of());
        }

        Sample first = samples.getFirst();
        Sample latest = samples.getLast();
        Duration observed = Duration.between(first.time(), latest.time());
        List<OwnerGrowth> growth = new ArrayList<>();

        latest.threadsByMod().forEach((id, latestCount) -> {
            int firstCount = first.threadsByMod().getOrDefault(id, 0);
            int difference = latestCount - firstCount;
            if (difference > 0) {
                classIndex.mod(id).ifPresent(mod -> growth.add(new OwnerGrowth(mod, firstCount, latestCount, difference)));
            }
        });
        growth.sort(Comparator.comparingInt(OwnerGrowth::growth).reversed());

        int totalGrowth = latest.totalThreads() - first.totalThreads();
        boolean enoughEvidence = samples.size() >= MINIMUM_SAMPLES
                && observed.compareTo(MINIMUM_OBSERVATION) >= 0;
        boolean suspicious = enoughEvidence
                && (totalGrowth >= SUSPICIOUS_TOTAL_GROWTH
                || growth.stream().anyMatch(owner -> owner.growth() >= SUSPICIOUS_MOD_GROWTH));

        return new Report(
                suspicious,
                samples.size(),
                observed,
                first.totalThreads(),
                latest.totalThreads(),
                growth.stream().limit(5).toList()
        );
    }

    public synchronized void reset() {
        samples.clear();
    }

    private static boolean isInfrastructureMod(String modId) {
    return modId.equals("minecraft")
            || modId.equals("java")
            || modId.equals("fabricloader")
            || modId.equals("fabric-api")
            || modId.startsWith("fabric-")
            || modId.startsWith("fabric_");
    }
}

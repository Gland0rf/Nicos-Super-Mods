package com.nico.client.memleak;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class ThreadLeakDetector {
    public record OwnerGrowth(
            ModIdentity mod,
            int firstCount,
            int latestCount,
            int growth,
            int positiveSteps,
            int negativeSteps,
            int newThreadSteps,
            int observedSamples,
            boolean sustainedGrowth
    ) { }

    public record Report(
            boolean suspicious,
            int samples,
            Duration observedFor,
            int firstThreadCount,
            int latestThreadCount,
            int totalPositiveSteps,
            int totalNegativeSteps,
            int totalNewThreadSteps,
            boolean totalSustainedGrowth,
            List<OwnerGrowth> ownerGrowth
    ) {
        public Report {
            ownerGrowth = List.copyOf(ownerGrowth);
        }

        public int totalGrowth() {
            return latestThreadCount - firstThreadCount;
        }

        public Optional<OwnerGrowth> sustainedOwnerGrowth() {
            return ownerGrowth.stream().filter(OwnerGrowth::sustainedGrowth).findFirst();
        }
    }

    private record Sample(
            Instant time,
            int totalThreads,
            Set<Long> threadIds,
            Map<String, Integer> threadsByMod,
            Map<String, Set<Long>> threadIdsByMod
    ) {
        private Sample {
            threadIds = Set.copyOf(threadIds);
            threadsByMod = Map.copyOf(threadsByMod);

            Map<String, Set<Long>> immutableIds = new HashMap<>();
            threadIdsByMod.forEach((id, ids) -> immutableIds.put(id, Set.copyOf(ids)));
            threadIdsByMod = Map.copyOf(immutableIds);
        }
    }

    private static final int MINIMUM_SAMPLES = 4;
    private static final Duration MINIMUM_OBSERVATION = Duration.ofMinutes(5);
    private static final int SUSPICIOUS_TOTAL_GROWTH = 8;
    private static final int SUSPICIOUS_MOD_GROWTH = 4;
    private static final int MINIMUM_GROWTH_STEPS = 3;
    private static final int MINIMUM_NEW_THREAD_STEPS = 2;

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
        Map<String, Set<Long>> idsByMod = new HashMap<>();
        Set<Long> threadIds = new HashSet<>();
        int total = 0;

        for (Map.Entry<Thread, StackTraceElement[]> entry : liveThreads.entrySet()) {
            Thread thread = entry.getKey();
            if (!thread.isAlive()) continue;
            total++;
            threadIds.add(thread.threadId());

            int inspected = 0;
            for (StackTraceElement frame : entry.getValue()) {
                if (inspected++ >= maximumStackFrames) break;
                var owner = classIndex.ownerOf(frame.getClassName());
                if (owner.isPresent() && !isInfrastructureMod(owner.get().id())) {
                    String modId = owner.get().id();
                    byMod.merge(modId, 1, Integer::sum);
                    idsByMod.computeIfAbsent(modId, ignored -> new HashSet<>()).add(thread.threadId());
                    break;
                }
            }
        }

        synchronized (this) {
            samples.addLast(new Sample(time, total, threadIds, byMod, idsByMod));
            Instant cutoff = time.minus(window);
            while (!samples.isEmpty() && samples.getFirst().time().isBefore(cutoff)) {
                samples.removeFirst();
            }
        }
    }

    public synchronized Report analyze(ModClassIndex classIndex) {
        if (samples.isEmpty()) {
            return new Report(false, 0, Duration.ZERO, 0, 0, 0, 0, 0, false, List.of());
        }

        List<Sample> history = List.copyOf(samples);
        Sample first = history.getFirst();
        Sample latest = history.getLast();
        Duration observed = Duration.between(first.time(), latest.time());
        boolean enoughEvidence = history.size() >= MINIMUM_SAMPLES && observed.compareTo(MINIMUM_OBSERVATION) >= 0;

        StepTrend totalTrend = analyzeCounts(history.stream().map(Sample::totalThreads).toList());
        int totalNewThreadSteps = countNewThreadSteps(history.stream().map(Sample::threadIds).toList());
        boolean totalSustainedGrowth = enoughEvidence && isSustainedGrowth(totalTrend, totalNewThreadSteps, SUSPICIOUS_TOTAL_GROWTH);

        Set<String> modIds = new HashSet<>();
        for (Sample sample : history) {
            modIds.addAll(sample.threadIdsByMod().keySet());
        }

        List<OwnerGrowth> growth = new ArrayList<>();
        for (String id : modIds) {
            List<Integer> counts = history.stream()
                    .map(sample -> sample.threadsByMod().getOrDefault(id, 0))
                    .toList();
            StepTrend trend = analyzeCounts(counts);
            if (trend.growth() <= 0) continue;

            int newThreadSteps = countNewOwnedThreadSteps(history, id);
            boolean sustained = enoughEvidence && isSustainedGrowth(trend, newThreadSteps, SUSPICIOUS_MOD_GROWTH);

            classIndex.mod(id).ifPresent(mod -> growth.add(new OwnerGrowth(
                    mod,
                    trend.first(),
                    trend.latest(),
                    trend.growth(),
                    trend.positiveSteps(),
                    trend.negativeSteps(),
                    newThreadSteps,
                    counts.size(),
                    sustained
            )));
        }

        growth.sort(
                Comparator.comparing(OwnerGrowth::sustainedGrowth).reversed()
                        .thenComparing(Comparator.comparingInt(OwnerGrowth::newThreadSteps).reversed())
                        .thenComparing(Comparator.comparingInt(OwnerGrowth::positiveSteps).reversed())
                        .thenComparing(Comparator.comparingInt(OwnerGrowth::growth).reversed())
        );

        boolean suspicious = totalSustainedGrowth || growth.stream().anyMatch(OwnerGrowth::sustainedGrowth);

        return new Report(
                suspicious,
                history.size(),
                observed,
                first.totalThreads(),
                latest.totalThreads(),
                totalTrend.positiveSteps(),
                totalTrend.negativeSteps(),
                totalNewThreadSteps,
                totalSustainedGrowth,
                growth.stream().limit(5).toList()
        );
    }

    public synchronized void reset() {
        samples.clear();
    }

    private static StepTrend analyzeCounts(List<Integer> counts) {
        if (counts.isEmpty()) {
            return new StepTrend(0, 0, 0, 0, 0);
        }

        int positiveSteps = 0;
        int negativeSteps = 0;
        int maximum = counts.getFirst();

        for (int index = 1; index < counts.size(); index++) {
            int previous = counts.get(index - 1);
            int current = counts.get(index);

            if (current > previous) positiveSteps++;
            else if (current < previous) negativeSteps++;

            maximum = Math.max(maximum, current);
        }

        return new StepTrend(
                counts.getFirst(),
                counts.getLast(),
                positiveSteps,
                negativeSteps,
                maximum
        );
    }

    private static int countNewThreadSteps(List<Set<Long>> idsBySample) {
        if (idsBySample.size() < 2) return 0;

        Set<Long> seen = new HashSet<>(idsBySample.getFirst());
        int stepsWithNewThreads = 0;

        for (int index = 1; index < idsBySample.size(); index++) {
            Set<Long> current = idsBySample.get(index);
            boolean sawNewThread = current.stream().anyMatch(id -> !seen.contains(id));
            if (sawNewThread) stepsWithNewThreads++;
            seen.addAll(current);
        }

        return stepsWithNewThreads;
    }

    private static int countNewOwnedThreadSteps(List<Sample> history, String modId) {
        if (history.size() < 2) return 0;

        Map<Long, Integer> firstSeenGlobally = new HashMap<>();
        for (int index = 0; index < history.size(); index++) {
            for (long threadId : history.get(index).threadIds()) {
                firstSeenGlobally.putIfAbsent(threadId, index);
            }
        }

        Set<Integer> creationSteps = new HashSet<>();
        for (Sample sample : history) {
            for (long threadId : sample.threadIdsByMod().getOrDefault(modId, Set.of())) {
                Integer firstSeen = firstSeenGlobally.get(threadId);
                if (firstSeen != null && firstSeen > 0) creationSteps.add(firstSeen);
            }
        }

        return creationSteps.size();
    }

    private static boolean isSustainedGrowth(StepTrend trend, int newThreadSteps, int minimumGrowth) {
        // A one-off jump (for example a renderer lazily creating a fixed worker pool)
        // is normal. Require repeated count increases and genuinely new thread IDs on
        // separate samples, while ensuring the latest count is still near the peak.
        return trend.growth() >= minimumGrowth
                && trend.positiveSteps() >= MINIMUM_GROWTH_STEPS
                && trend.positiveSteps() > trend.negativeSteps()
                && newThreadSteps >= MINIMUM_NEW_THREAD_STEPS
                && trend.latest() >= trend.maximum() - 1;
    }

    private record StepTrend(
            int first,
            int latest,
            int positiveSteps,
            int negativeSteps,
            int maximum
    ) {
        private int growth() {
            return latest - first;
        }
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

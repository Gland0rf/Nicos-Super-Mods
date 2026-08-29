package com.nico.client.memleak;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class MemLeakService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("NSM/MemLeak");

    private static final double MIB = 1024.0 * 1024.0;

    private final MemLeakConfig config;
    private final Path reportDirectory;
    private final Consumer<String> notifier;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final GcMonitor gcMonitor;

    private volatile ModClassIndex classIndex = ModClassIndex.empty();
    private volatile HeapTrendAnalyzer analyzer;
    private volatile AllocationSampler allocationSampler;
    private volatile String startupStatus = "Starting";
    private volatile Instant lastAlert = Instant.EPOCH;

    public MemLeakService(MemLeakConfig config, Path reportDirectory, Consumer<String> notifier) {
        this.config = config;
        this.reportDirectory = reportDirectory;
        this.notifier = notifier;
        this.analyzer = new HeapTrendAnalyzer(config, classIndex);
        this.gcMonitor = new GcMonitor(this::handleGc);
    }

    public void start() {
        gcMonitor.start();
        Thread.ofPlatform().daemon().name("NSM-MemLeak-Indexer").start(() -> {
            try {
                startupStatus = "Indexing loaded mod clauses";
                ModClassIndex builtIndex = ModClassIndex.build();
                if (closed.get()) return;
                classIndex = builtIndex;
                HeapTrendAnalyzer replacement = new HeapTrendAnalyzer(config, builtIndex);
                for (HeapSnapshot snapshot : analyzer.history()) {
                    replacement.add(snapshot);
                }
                analyzer = replacement;

                AllocationSampler sampler = new AllocationSampler(builtIndex, config.maximumStackFrames());
                allocationSampler = sampler;
                sampler.start();
                startupStatus = sampler.isRunning()
                        ? "Monitoring"
                        : "Heap monitoring active; allocation sampling unavailable";
            } catch (Throwable error) {
                startupStatus = "Heap monitoring active; mod indexing failed: " + error.getClass().getSimpleName();
            }
        });
    }

    private void handleGc(GcMonitor.Observation observation) {
        AllocationSampler sampler = allocationSampler;
        Map<String, Long> allocations = sampler == null ? Map.of() : sampler.snapshot();
        HeapSnapshot snapshot = new HeapSnapshot(
                observation.time(),
                observation.heapUsedBytes(),
                allocations,
                observation.collector(),
                observation.cause()
        );
        HeapTrendAnalyzer currentAnalyzer = analyzer;
        currentAnalyzer.add(snapshot);
        AnalysisReport report = currentAnalyzer.analyze(maxHeap());

        if (report.state() == AnalysisReport.State.SUSPICIOUS
                && Duration.between(lastAlert, observation.time()).compareTo(config.alertCooldown()) >= 0) {
            lastAlert = observation.time();
            String candidate = report.candidates().isEmpty()
                    ? "no allocation candidate yet"
                    : "top allocation candidate: " + report.candidates().getFirst().mod().displayName();
            notifier.accept(String.format(
                    Locale.ROOT,
                    "[NSM] Possible sustained heap growth: %+.1f MiB/min; %s. Run /nsm memleak status.",
                    report.growthRateBytesPerMinute() / MIB,
                    candidate
            ));
        }
    }

    public List<String> statusLines() {
        var report = analyzer.analyze(maxHeap());

        logTechnicalReport(report);

        if (report.postGcSamples() == 0) {
            return List.of(
                    "§b[NSM MemLeak] §fLearning your memory baseline...",
                    "§7Waiting for the first full memory cleanup."
            );
        }

        double latestMiB = report.latestPostGcHeapBytes() / MIB;
        double growthMiB = Math.max(0, report.observedGrowthBytes()) / MIB;
        String observedTime = formatDuration(report.observedFor());

        return switch (report.state()) {
            case WARMING_UP -> List.of(
                    "§b[NSM MemLeak] §fLearning your memory baseline...",
                    "§7Observed for §e"
                            + formatDuration(report.observedFor())
                            + "§7/§e"
                            + formatDuration(config.minimumObservation())
                            + "§7 with §e"
                            + report.postGcSamples()
                            + " §7memory cleanups.",
                    "§7Current memory after cleanup: §f"
                            + formatNumber(latestMiB)
                            + " MiB"
            );

            case STABLE -> List.of(
                    "§a[NSM MemLeak] §fNo memory leak detected.",
                    "§7Memory after cleanup: §f"
                            + formatNumber(latestMiB)
                            + " MiB",
                    "§8No action is currently needed."
            );

            case SUSPICIOUS -> List.of(
                    "§c[NSM MemLeak] §fPossible memory leak detected.",
                    "§7Memory kept after cleanup increased by §c"
                            + formatNumber(growthMiB)
                            + " MiB §7over §e"
                            + observedTime
                            + "§7.",
                    "§eRun §f/nsm memleak suspects §efor likely sources."
            );
        };
    }

    public List<String> candidateLines() {
        var report = analyzer.analyze(maxHeap());

        logTechnicalReport(report);

        if (!"SUSPICIOUS".equals(report.state().name())) {
            return List.of(
                    "§a[NSM MemLeak] §fNo active leak suspicion.",
                    "§7Possible sources will appear here if sustained growth is detected."
            );
        }

        if (report.candidates().isEmpty()) {
            return List.of(
                    "§e[NSM MemLeak] §fMemory growth was detected,",
                    "§7but no individual mod could be identified yet.",
                    "§7Continue playing and check again later."
            );
        }

        List<String> lines = new ArrayList<>();

        lines.add("§c[NSM MemLeak] §fMost likely allocation sources:");

        int position = 1;

        for (var candidate : report.candidates().stream().limit(3).toList()) {
            lines.add(
                    "§e"
                            + position++
                            + ". §f"
                            + candidate.mod().name()
                            + " §8- §7"
                            + formatPercentage(candidate.allocationShare())
                            + " of sampled allocations"
            );
        }

        lines.add("§8These are diagnostic leads, not definitive proof.");

        return List.copyOf(lines);
    }

    public List<String> modIndexLines() {
        var visibleMods = classIndex.mods().stream()
                .filter(mod -> !isInfrastructureMod(mod.id()))
                .sorted(
                        Comparator.comparing(
                                mod -> mod.name().toLowerCase(Locale.ROOT)
                        )
                )
                .toList();

        LOGGER.info(
                "MemLeak indexed {} classes, {} ambiguous classes and {} total mod containers",
                classIndex.indexedClassCount(),
                classIndex.ambiguousClassCount(),
                classIndex.mods().size()
        );

        for (var mod : visibleMods) {
            LOGGER.info(
                    "MemLeak monitored mod: id={}, name={}, version={}",
                    mod.id(),
                    mod.name(),
                    mod.version()
            );
        }

        List<String> lines = new ArrayList<>();
        lines.add("§b[NSM MemLeak] §fMonitoring §e" + visibleMods.size() + " §fmods.");

        int shown = Math.min(18, visibleMods.size());

        for (int start = 0; start < shown; start += 6) {
            int end = Math.min(start + 6, shown);

            String names = visibleMods.subList(start, end).stream()
                    .map(mod -> mod.name())
                    .reduce((left, right) -> left + "§8, §7" + right)
                    .orElse("");

            lines.add("§7" + names);
        }

        if (visibleMods.size() > shown) {
            lines.add("§8...and " + (visibleMods.size() - shown) + " more. The complete list is in latest.log.");
        }

        return List.copyOf(lines);
    }

    public void reset() {
        analyzer.reset();
        AllocationSampler sampler = allocationSampler;
        if (sampler != null) {
            sampler.reset();
        }
        lastAlert = Instant.EPOCH;
    }

    public Path exportReport() throws IOException {
        AllocationSampler sampler = allocationSampler;
        return ReportWriter.write(
                reportDirectory,
                analyzer.analyze(maxHeap()),
                analyzer.history(),
                classIndex.mods(),
                sampler != null && sampler.isRunning(),
                sampler == null ? null : sampler.failureReason()
        );
    }

    private long maxHeap() {
        long max = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
        return max > 0 ? max : Runtime.getRuntime().maxMemory();
    }

    private void logTechnicalReport(AnalysisReport report) {
        LOGGER.info(
                "MemLeak analysis: state={}, samples={}, observedSeconds={}, "
                        + "firstPostGcBytes={}, latestPostGcBytes={}, "
                        + "growthBytes={}, rateBytesPerMinute={}, "
                        + "rSquared={}, monotonicity={}",
                report.state(),
                report.postGcSamples(),
                report.observedFor().toSeconds(),
                report.firstPostGcHeapBytes(),
                report.latestPostGcHeapBytes(),
                report.observedGrowthBytes(),
                report.growthRateBytesPerMinute(),
                report.rSquared(),
                report.monotonicity()
        );

        for (var candidate : report.candidates()) {
            LOGGER.info(
                    "MemLeak candidate: id={}, name={}, version={}, "
                            + "sampledAllocationBytes={}, share={}",
                    candidate.mod().id(),
                    candidate.mod().name(),
                    candidate.mod().version(),
                    candidate.sampledAllocationBytes(),
                    candidate.allocationShare()
            );
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

    private static String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatPercentage(double share) {
        return String.format(Locale.ROOT, "%.0f%%", share * 100.0);
    }

    private static String formatDuration(Duration duration) {
        long minutes = duration.toMinutes();

        if (minutes < 60) {
            return minutes + " min";
        }

        long hours = minutes / 60;
        long remainingMinutes = minutes % 60;

        if (remainingMinutes == 0) {
            return hours + (hours == 1 ? " hour" : " hours");
        }

        return hours + "h " + remainingMinutes + "m";
    }

    @Override
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        gcMonitor.close();
        AllocationSampler sampler = allocationSampler;
        if (sampler != null) {
            sampler.close();
        }
    }
}

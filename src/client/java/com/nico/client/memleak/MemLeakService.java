package com.nico.client.memleak;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class MemLeakService implements AutoCloseable {
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
                    "[NSM] Possible sustained heap growth: %+.1f MiB/min; %s. Run /memdoctor status.",
                    report.growthRateBytesPerMinute() / MIB,
                    candidate
            ));
        }
    }

    public List<String> statusLines() {
        AnalysisReport report = analyzer.analyze(maxHeap());
        return List.of(
                "[NSM] " + report.state() + " - " + report.explanation(),
                String.format(Locale.ROOT, "Post-GC heap: %.1f -> %.1f MiB across %d samples (%.1f min)",
                        report.firstPostGcHeapBytes() / MIB,
                        report.latestPostGcHeapBytes() / MIB,
                        report.postGcSamples(),
                        report.observedFor().toMillis() / 60_000.0),
                String.format(Locale.ROOT, "Trend: %+.1f MiB/min, R² %.2f, monotonicity %.0f%%",
                        report.growthRateBytesPerMinute() / MIB,
                        report.rSquared(),
                        report.monotonicity() * 100),
                "Engine: " + startupStatus + "; indexed " + classIndex.indexedClassCount() + " classes from " + classIndex.mods().size() + " mods"
        );
    }

    public List<String> candidateLines() {
        AnalysisReport report = analyzer.analyze(maxHeap());
        if (report.candidates().isEmpty()) {
            return List.of("[MemDoctor] No mod allocation candidates are available yet.");
        }
        var lines = new java.util.ArrayList<String>();
        lines.add("[MemDoctor] Allocation candidates in the current trend window (not retained-memory proof):");
        for (AnalysisReport.Candidate candidate : report.candidates()) {
            lines.add(String.format(Locale.ROOT, "%s %s — %.1f MiB sampled (%.1f%%)",
                    candidate.mod().name(),
                    candidate.mod().version(),
                    candidate.sampledAllocationBytes() / MIB,
                    candidate.allocationShare() * 100));
        }
        return List.copyOf(lines);
    }

    public List<String> modIndexLines() {
        var lines = new java.util.ArrayList<String>();
        lines.add("[MemDoctor] Indexed " + classIndex.indexedClassCount() + " classes; "
                + classIndex.ambiguousClassCount() + " duplicate/ambiguous classes.");
        classIndex.mods().stream().limit(40).forEach(mod ->
                lines.add(mod.id() + " " + mod.version() + " — " + mod.name()));
        if (classIndex.mods().size() > 40) {
            lines.add("...and " + (classIndex.mods().size() - 40) + " more mods (export a report for the complete list).");
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

package com.nico.client.memleak;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
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
    private final ActivityTimeline activityTimeline = new ActivityTimeline();
    private final LifecycleLeakDetector lifecycleLeakDetector = new LifecycleLeakDetector();
    private final ThreadLeakDetector threadLeakDetector;

    private volatile ModClassIndex classIndex = ModClassIndex.empty();
    private volatile HeapTrendAnalyzer analyzer;
    private volatile AllocationSampler allocationSampler;
    private volatile String startupStatus = "Starting";
    private volatile Instant lastAlert = Instant.EPOCH;
    private WeakReference<Object> currentScreen = new WeakReference<>(null);

    public MemLeakService(MemLeakConfig config, Path reportDirectory, Consumer<String> notifier) {
        this.config = config;
        this.reportDirectory = reportDirectory;
        this.notifier = notifier;
        this.analyzer = new HeapTrendAnalyzer(config, classIndex);
        this.threadLeakDetector = new ThreadLeakDetector(config.window(), config.maximumStackFrames());
        this.gcMonitor = new GcMonitor(this::handleGc);
    }

    public void start() {
        gcMonitor.start();
        Thread.ofPlatform().daemon().name("NSM-MemLeak-Indexer").start(() -> {
            try {
                startupStatus = "Indexing loaded mod classes";
                ModClassIndex builtIndex = ModClassIndex.build();
                if (closed.get()) return;
                classIndex = builtIndex;
                threadLeakDetector.reset();
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
        lifecycleLeakDetector.onMajorCollection();
        try {
            threadLeakDetector.sample(observation.time(), classIndex);
        } catch (Throwable error) {
            LOGGER.debug("MemLeak thread sampling failed", error);
        }
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
        List<LifecycleLeakDetector.Suspect> lifecycleSuspects = lifecycleLeakDetector.suspects(observation.time());
        ThreadLeakDetector.Report threadReport = threadLeakDetector.analyze(classIndex);

        if ((report.state() == AnalysisReport.State.SUSPICIOUS
                || !lifecycleSuspects.isEmpty()
                || threadReport.suspicious())
                && Duration.between(lastAlert, observation.time()).compareTo(config.alertCooldown()) >= 0) {
            lastAlert = observation.time();
            if (report.state() == AnalysisReport.State.SUSPICIOUS) {
                String candidate = report.candidates().isEmpty()
                        ? "no allocation candidate yet"
                        : "top allocation candidate: " + report.candidates().getFirst().mod().displayName();
                notifier.accept(String.format(
                        Locale.ROOT,
                        "[NSM] Possible sustained heap growth: %+.1f MiB/min; %s. Run /nsm memleak status.",
                        report.growthRateBytesPerMinute() / MIB,
                        candidate
                ));
            } else if (!lifecycleSuspects.isEmpty()) {
                LifecycleLeakDetector.Suspect suspect = lifecycleSuspects.getFirst();
                notifier.accept(
                        "[NSM] Possible lifecycle leak: " + suspect.description()
                                + " survived " + suspect.completedMajorCollections()
                                + " full cleanups. Run /nsm memleak suspects."
                );
            } else {
                notifier.accept(
                        "[NSM] Possible thread leak: " + threadReport.totalGrowth()
                                + " additional live threads. Run /nsm memleak suspects."
                );
            }
        }
    }

    /** Called from the tick hook. No strong client-object references are retained. */
    public void observeClientState(Object level, Object player, Object screen) {
        if (closed.get()) {
            return;
        }

        Instant now = Instant.now();
        if (lifecycleLeakDetector.observe("world", level, describe(level, "Client world"), now)) {
            activityTimeline.mark("world", level == null ? "Left a world" : "Joined or changed world");
        }
        lifecycleLeakDetector.observe("player", player, describe(player, "Client player"), now);
        observeScreenActivity(screen);
    }

    private synchronized void observeScreenActivity(Object screen) {
        if (currentScreen.get() == screen) return;

        currentScreen = new WeakReference<>(screen);
        activityTimeline.mark("screen",
                screen == null ? "Closed a screen" : "Opened " + readableClassName(screen.getClass())
        );
    }

    public void markActivity(String type, String description) {
        if (closed.get() || type == null || description == null) return;

        String safeType = type.strip();
        String safeDescription = description.strip();
        if (safeType.isEmpty() || safeDescription.isEmpty()) return;

        activityTimeline.mark(
                safeType.substring(0, Math.min(32, safeType.length())),
                safeDescription.substring(0, Math.min(160, safeDescription.length()))
        );
    }

    public List<String> statusLines() {
        var report = analyzer.analyze(maxHeap());
        var lifecycleSuspects = lifecycleLeakDetector.suspects(Instant.now());
        var threadReport = threadLeakDetector.analyze(classIndex);

        logTechnicalReport(report);

        if (report.postGcSamples() == 0) {
            List<String> lines = new ArrayList<>(List.of(
                    "§b[NSM MemLeak] §fLearning your memory baseline...",
                    "§7Waiting for the first full memory cleanup."
            ));
            appendLifecycleWarning(lines, lifecycleSuspects);
            return List.copyOf(lines);
        }

        double latestMiB = report.latestPostGcHeapBytes() / MIB;
        double growthMiB = Math.max(0, report.observedGrowthBytes()) / MIB;
        String observedTime = formatDuration(report.observedFor());

        if (!lifecycleSuspects.isEmpty() && report.state() != AnalysisReport.State.SUSPICIOUS) {
            LifecycleLeakDetector.Suspect first = lifecycleSuspects.getFirst();
            return List.of(
                    "§c[NSM MemLeak] §fPossible lifecycle leak detected.",
                    "§f" + first.description() + " §7survived §e"
                            + first.completedMajorCollections() + " §7full cleanups.",
                    "§8Sustained total heap growth is not confirmed yet.",
                    "§eRun §f/nsm memleak suspects §efor details."
            );
        }

        if (threadReport.suspicious() && report.state() != AnalysisReport.State.SUSPICIOUS) {
            return List.of(
                    "§c[NSM MemLeak] §fPossible thread leak detected.",
                    "§7Live threads increased by §c " + threadReport.totalGrowth()
                            + " §7over §e" + formatDuration(threadReport.observedFor()) + "§7.",
                    "§eRun §f/nsm memleak suspects §e for details."
            );
        }

        List<String> lines = new ArrayList<>(switch (report.state()) {
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
        });

        appendLifecycleWarning(lines, lifecycleSuspects);
        if (report.state() == AnalysisReport.State.SUSPICIOUS) {
            ActivityTimeline.Event latest = activityTimeline.latestSignificantEvent();
            if (latest != null) {
                lines.add("§8 Latest tracked activity: " + latest.description() + ".");
            }
        }
        return List.copyOf(lines);
    }

    public List<String> candidateLines() {
        var report = analyzer.analyze(maxHeap());
        var lifecycleSuspects = lifecycleLeakDetector.suspects(Instant.now());
        var threadReport = threadLeakDetector.analyze(classIndex);

        logTechnicalReport(report);

        if (report.state() != AnalysisReport.State.SUSPICIOUS
                && lifecycleSuspects.isEmpty()
                && !threadReport.suspicious()) {
            return List.of(
                    "§a[NSM MemLeak] §fNo active leak suspicion.",
                    "§7Possible sources will appear here if sustained growth is detected."
            );
        }

        if (report.candidates().isEmpty()
                && lifecycleSuspects.isEmpty()
                && !threadReport.suspicious()) {
            return List.of(
                    "§e[NSM MemLeak] §fMemory growth was detected,",
                    "§7but no individual mod could be identified yet.",
                    "§7Continue playing and check again later."
            );
        }

        List<String> lines = new ArrayList<>();

        if (!lifecycleSuspects.isEmpty()) {
            lines.add("§c[NSM MemLeak] §fObjects that should have unloaded:");
            for (var suspect : lifecycleSuspects.stream().limit(1).toList()) {
                lines.add(
                        "§e• §f" + suspect.description()
                                + " §7is still alive after §e"
                                + suspect.completedMajorCollections()
                                + "§7 full cleanups."
                );
            }
        }

        if (threadReport.suspicious()) {
            lines.add("§cThread growth:");
            lines.add(
                    "§7Live threads increased from §f" + threadReport.firstThreadCount()
                            + " §7 to §f" + threadReport.latestThreadCount() + "§7."
            );
            for (var owner : threadReport.ownerGrowth().stream().limit(1).toList()) {
                lines.add(
                        "§e• §f" + owner.mod().name()
                                + " §7has §c+" + owner.growth() + " §7attributed threads"
                );
            }
        }

        if (report.state() != AnalysisReport.State.SUSPICIOUS) {
            lines.add("§8Sustained total heap grwoth is not confirmed yet.");
            return List.copyOf(lines);
        }

        if (report.candidates().isEmpty()) {
            lines.add("§7No individual allocating mod could be identified yet.");
            lines.add("§8Continue playing and export a report after more cleanups.");
            return List.copyOf(lines);
        }

        lines.add("§c[NSM MemLeak] §fMost likely allocation sources:");

        int position = 1;

        for (var candidate : report.candidates().stream().limit(2).toList()) {
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
        lifecycleLeakDetector.reset();
        activityTimeline.reset();
        threadLeakDetector.reset();
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
                sampler == null ? null : sampler.failureReason(),
                activityTimeline.snapshot(),
                lifecycleLeakDetector.suspects(Instant.now()),
                threadLeakDetector.analyze(classIndex)
        );
    }

    private static void appendLifecycleWarning(List<String> lines, List<LifecycleLeakDetector.Suspect> lifecycleSuspects) {
        if (lifecycleSuspects.isEmpty()) return;

        LifecycleLeakDetector.Suspect first = lifecycleSuspects.getFirst();
        lines.add(
                "§cLifecycle warning: §f" + first.description()
                        + " §7survived §e"
                        + first.completedMajorCollections()
                        + " §7full cleanups."
        );
        lines.add("§eRun §f/nsm memleak suspects §efor details.");
    }

    private static String describe(Object value, String fallback) {
        return value == null ? fallback : fallback + " (" + readableClassName(value.getClass()) + ")";
    }

    private static String readableClassName(Class<?> type) {
        String simple = type.getSimpleName();
        return simple == null || simple.isBlank() ? type.getName() : simple;
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

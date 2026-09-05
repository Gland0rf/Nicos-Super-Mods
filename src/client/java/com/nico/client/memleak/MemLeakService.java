package com.nico.client.memleak;

import com.terraformersmc.modmenu.util.mod.Mod;
import net.minecraft.util.ModCheck;
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
    private volatile Instant monitoringStartedAt = Instant.now();
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
            LOGGER.debug("Memory thread sampling failed", error);
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

        boolean suspicious = report.state() == AnalysisReport.State.SUSPICIOUS || !lifecycleSuspects.isEmpty() || threadReport.suspicious();
        if (!suspicious || Duration.between(lastAlert, observation.time()).compareTo(config.alertCooldown()) < 0) return;

        lastAlert = observation.time();
        Attribution attribution = bestAttribution(report, threadReport);

        if (report.state() == AnalysisReport.State.SUSPICIOUS) {
            String source = attribution.actionable()
                    ? " Likely source: " + attribution.mod().displayNameWithVersion() + "."
                    : " I can't identify a likely mod yet.";
            notifier.accept(
                    "[NSM] Memory may be leaking." + source
                            + " Run /nsm memleak diagnose for what to do next."
            );
            return;
        }

        if (!lifecycleSuspects.isEmpty()) {
            LifecycleLeakDetector.Suspect suspect = lifecycleSuspects.getFirst();
            notifier.accept(
                    "[NSM] Old " + friendlyObjectName(suspect.kind())
                            + " data is still in memory after it should have unloaded. "
                            + "Run /nsm memleak diagnose for next steps."
            );
            return;
        }

        String source = attribution.actionable()
                ? " Likely source: " + attribution.mod().displayNameWithVersion() + "."
                : "";
        notifier.accept("[NSM] Background tasks keep increasing." + source
                + " Run /nsm memory diagnose for next steps.");
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
        AnalysisReport report = analyzer.analyze(maxHeap());
        List<LifecycleLeakDetector.Suspect> lifecycleSuspects = lifecycleLeakDetector.suspects(Instant.now());
        ThreadLeakDetector.Report threadReport = threadLeakDetector.analyze(classIndex);

        logTechnicalReport(report);

        if (report.postGcSamples() == 0) {
            List<String> lines = new ArrayList<>(List.of(
                    "§b[NSM Memory Check] §fLearning what's normal...",
                    "§7Monitoring for §e" + formatDuration(Duration.between(monitoringStartedAt, Instant.now())) + "§7.",
                    "§7Waiting for Minecraft to perform its first full memory cleanup."
            ));
            appendLifecycleWarning(lines, lifecycleSuspects);
            return List.copyOf(lines);
        }

        double latestMiB = report.latestPostGcHeapBytes() / MIB;
        double maxMiB = report.maximumHeapBytes() / MIB;
        double growthMiB = Math.max(0, report.observedGrowthBytes()) / MIB;
        String observedTime = formatDuration(report.observedFor());
        Attribution attribution = bestAttribution(report, threadReport);

        if (report.state() == AnalysisReport.State.SUSPICIOUS) {
            List<String> lines = new ArrayList<>();
            lines.add("§c[NSM Memory Check] §fMemory may be leaking.");
            lines.add(
                    "§7Minecraft is keeping §c" + formatNumber(growthMiB)
                            + " MiB §7more than §e" + observedTime + " §7ago."
            );
            if (attribution.actionable()) {
                lines.add("§7Likely source: §f" + attribution.mod().displayNameWithVersion()
                        + " §8(" + attribution.confidence().label + " confidence)");
            } else if (attribution.mod() != null) {
                lines.add("§7Possible source: §f" + attribution.mod().displayNameWithVersion()
                        + " §8(low confidence)");
            } else {
                lines.add("§7Likely source: §fUnknown §8(not enough evidence yet)");
            }
            lines.add(memoryPressureLine(report, latestMiB, maxMiB));
            lines.add("§eRun §f/nsm memleak diagnose §efor recommended next steps.");
            appendLifecycleWarning(lines, lifecycleSuspects);
            return List.copyOf(lines);
        }

        if (!lifecycleSuspects.isEmpty()) {
            LifecycleLeakDetector.Suspect first = lifecycleSuspects.getFirst();
            return List.of(
                    "§c[NSM Memory Check] §fOld game data is not being released.",
                    "§7An old " + friendlyObjectName(first.kind())
                            + " is still in memory after it should have unloaded.",
                    memoryPressureLine(report, latestMiB, maxMiB),
                    "§eRun §f/nsm memleak diagnose §efor recommended next steps."
            );
        }

        if (threadReport.suspicious()) {
            List<String> lines = new ArrayList<>();
            lines.add("§c[NSM Memory Check] §fBackground tasks keep increasing.");
            appendThreadTrendSummary(lines, threadReport);
            if (attribution.actionable()) {
                lines.add("§7Likely source: §f" + attribution.mod().displayNameWithVersion()
                        + " §8(" + attribution.confidence().label + " confidence)");
            } else if (attribution.mod() != null) {
                lines.add("§7Possible source: §f" + attribution.mod().displayNameWithVersion()
                        + " §8(low confidence)");
            } else {
                lines.add("§7Likely source: §fUnknown §8(not enough evidence yet)");
            }
            lines.add("§eRun §f/nsm memleak diagnose §efor recommended next steps.");
            return List.copyOf(lines);
        }

        return switch (report.state()) {
            case WARMING_UP -> {
                List<String> lines = new ArrayList<>();
                lines.add("§b[NSM Memory Check] §fLearning what's normal...");
                lines.add("§7Monitoring for §e"
                        + formatDuration(Duration.between(monitoringStartedAt, Instant.now())) + "§7.");
                lines.add("§7Full cleanup samples: §e" + report.postGcSamples()
                        + "§7/§e" + config.minimumSamples() + "§7.");

                if (report.postGcSamples() >= 2 && report.observedFor().compareTo(config.minimumObservation()) < 0) {
                    lines.add("§8Those samples currently span " + formatDuration(report.observedFor())
                            + "; they need to be spread across at least "
                            + formatDuration(config.minimumObservation()) + ".");
                } else if (report.postGcSamples() < config.minimumSamples()) {
                    lines.add("§8Waiting for more automatic Minecraft memory cleanups.");
                }

                lines.add("§7Memory after latest cleanup: §f" + formatNumber(latestMiB)
                        + " / " + formatNumber(maxMiB) + " MiB");
                yield List.copyOf(lines);
            }

            case STABLE -> List.of(
                    "§a[NSM Memory Check] §fMemory looks healthy.",
                    "§7Memory after cleanup: §f" + formatNumber(latestMiB)
                            + " / " + formatNumber(maxMiB) + " MiB",
                    "§8No steady increase was detected. No action needed."
            );
            case SUSPICIOUS -> throw new IllegalStateException("handled above");
        };
    }

    public List<String> diagnosisLines() {
        AnalysisReport report = analyzer.analyze(maxHeap());
        List<LifecycleLeakDetector.Suspect> lifecycleSuspects = lifecycleLeakDetector.suspects(Instant.now());
        ThreadLeakDetector.Report threadReport = threadLeakDetector.analyze(classIndex);
        Attribution attribution = bestAttribution(report, threadReport);

        logTechnicalReport(report);

        boolean suspicious = report.state() == AnalysisReport.State.SUSPICIOUS
                || !lifecycleSuspects.isEmpty()
                || threadReport.suspicious();

        if (!suspicious) {
            return List.of(
                    "§e[NSM Memory Check] §fNothing suspicious right now.",
                    "§7No action is needed."
            );
        }

        List<String> lines = new ArrayList<>();

        lines.add("§c[NSM Memory Check] §fWhat you can do");

        if (attribution.actionable()) {
            ModIdentity mod = attribution.mod();
            lines.add("§7Likely source: §f" + mod.displayNameWithVersion());
            lines.add("§7Confidence: §f" + attribution.confidence().label);

            if (isNsm(mod)) {
                lines.add("§aNSM can safely clear its temporary world/run data.");
                lines.add("§7With Auto-clean enabled, this happens when you leave or change worlds.");
                lines.add("§7Run §f/nsm memleak cleanup §7when you're not in the middle of a dungeon run.");
                lines.add("§8Wiki caches, PBs, routes, layouts, and settings are not cleared.");
                lines.add("§7If memory still grows afterward, create a report so the NSM issue can be fixed.");
            } else {
                lines.add("§e1. §fCheck whether " + mod.name() + " has an update available.");
                lines.add("§e2. §fRun /nsm memleak report before restarting Minecraft.");
                lines.add(restartAdviceLine(report));
                lines.add("§e4. §fIf it happens again, send the report to the " + mod.name() + " developer.");
                if (!mod.supportUrl().isBlank()) {
                    lines.add("§7Report/update page: §f" + mod.supportUrl());
                }
            }

        } else {
            if (attribution.mod() != null) {
                lines.add("§7Possible source: §f" + attribution.mod().displayNameWithVersion());
                lines.add("§7Confidence: §fLow");
            } else {
                lines.add("§7Likely source: §fUnknown");
                lines.add("§7There isn't enough evidence to point at a specific mod yet.");
            }
            lines.add("§e1. §fKeep playing for a little longer so NSM can collect more evidence.");
            lines.add("§e2. §fRun /nsm memleak report before restarting if the problem continues.");
            lines.add(restartAdviceLine(report));
            lines.add("§8Don't disable random mods based only on a low-confidence result.");
        }

        if (!lifecycleSuspects.isEmpty()) {
            LifecycleLeakDetector.Suspect first = lifecycleSuspects.getFirst();
            lines.add("§8Extra clue: an old " + friendlyObjectName(first.kind())
                    + " stayed in memory through " + first.completedMajorCollections() + " cleanups.");
        }

        if (threadReport.suspicious()) {
            ThreadLeakDetector.OwnerGrowth owner = threadReport.sustainedOwnerGrowth().orElse(null);
            if (owner != null) {
                lines.add("§8Extra clue: " + owner.mod().name() + " increased from "
                        + owner.firstCount() + " to " + owner.latestCount()
                        + " attributed background threads across " + owner.positiveSteps() + " separate checks, "
                        + "with new threads appearing on " + owner.newThreadSteps() + " of them.");
            } else if (threadReport.totalSustainedGrowth()) {
                lines.add("§8Extra clue: total background threads increased by " + threadReport.totalGrowth()
                        + " across " + threadReport.totalPositiveSteps() + " separate checks.");
            }
        }

        lines.add("§8A likely source is a clue, not proof that the mod is definitely responsible.");
        return List.copyOf(lines);
    }

    /** Backwards-compatible method used by older command wiring. */
    public List<String> candidateLines() {
        return diagnosisLines();
    }

    public List<String> reportAdviceLines() {
        AnalysisReport report = analyzer.analyze(maxHeap());
        ThreadLeakDetector.Report threadReport = threadLeakDetector.analyze(classIndex);
        Attribution attribution = bestAttribution(report, threadReport);

        if (attribution.actionable()) {
            ModIdentity mod = attribution.mod();
            List<String> lines = new ArrayList<>();
            if (isNsm(mod)) {
                lines.add("§7If the problem happens again, attach this report when reporting it to NSM.");
            } else {
                lines.add("§7If the problem happens again, send this report to the §f" + mod.name() + " §7developer.");
                lines.add("§7Also check whether §f" + mod.name() + " §7has an update available.");
                if (!mod.supportUrl().isBlank()) {
                    lines.add("§7Report/update page: §f" + mod.supportUrl());
                }
            }
            lines.add("§8The report contains diagnostic memory/mod data, not your chat messages or account credentials.");
            return List.copyOf(lines);
        }

        return List.of(
                "§7Keep this report if the problem continues; it contains the evidence NSM has collected so far.",
                "§8The report contains diagnostic memory/mod data, not your chat messages or account credentials."
        );
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
        lines.add("§b[NSM Memory Check] §fChecking §e" + visibleMods.size() + " §fmods for memory-related clues.");

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
            lines.add("§8...and " + (visibleMods.size() - shown) + " more. The full list is in latest.log.");
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
        monitoringStartedAt = Instant.now();
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

    private Attribution bestAttribution(AnalysisReport report, ThreadLeakDetector.Report threadReport) {
        AnalysisReport.Candidate heapCandidate =
                report.state() == AnalysisReport.State.SUSPICIOUS && !report.candidates().isEmpty()
                        ? report.candidates().getFirst()
                        : null;
        ThreadLeakDetector.OwnerGrowth threadCandidate =
                threadReport.suspicious()
                        ? threadReport.sustainedOwnerGrowth().orElse(null)
                        : null;

        if (heapCandidate != null && threadCandidate != null
                && heapCandidate.mod().id().equals(threadCandidate.mod().id())) {
            return new Attribution(heapCandidate.mod(), Confidence.HIGH);
        }

        if (heapCandidate != null) {
            double topShare = heapCandidate.allocationShare();
            double secondShare = report.candidates().size() > 1
                    ? report.candidates().get(1).allocationShare()
                    : 0.0;
            boolean clearlyAhead = secondShare == 0.0 || topShare >= secondShare * 1.5;
            Confidence confidence = topShare >= 0.45 && clearlyAhead ? Confidence.MODERATE : Confidence.LOW;
            return new Attribution(heapCandidate.mod(), confidence);
        }

        if (threadCandidate != null) {
            // Thread growth alone is only actionable after the heap detector has finished
            // warming up. This avoids blaming mods for normal lazy worker-pool startup.
            if (report.state() == AnalysisReport.State.WARMING_UP) {
                return new Attribution(threadCandidate.mod(), Confidence.LOW);
            }

            Confidence confidence = threadCandidate.growth() >= 8
                    && threadCandidate.positiveSteps() >= 4
                    && threadCandidate.newThreadSteps() >= 3
                    ? Confidence.MODERATE
                    : Confidence.LOW;
            return new Attribution(threadCandidate.mod(), confidence);
        }

        return Attribution.NONE;
    }

    private static void appendThreadTrendSummary(List<String> lines, ThreadLeakDetector.Report threadReport) {
        ThreadLeakDetector.OwnerGrowth owner = threadReport.sustainedOwnerGrowth().orElse(null);

        if (threadReport.totalSustainedGrowth()) {
            lines.add("§7Total background threads increased by §c" + threadReport.totalGrowth()
                    + " §7across §e" + threadReport.totalPositiveSteps() + " §7separate checks.");
        } else if (threadReport.totalGrowth() <= 0) {
            lines.add("§7Overall background thread count changed by §a" + threadReport.totalGrowth()
                    + "§7, so the warning is based on a specific mod's repeated growth.");
        } else {
            lines.add("§7Overall background thread count changed by §e+" + threadReport.totalGrowth() + "§7.");
        }

        if (owner != null) {
            lines.add("§7" + owner.mod().name() + " rose from §f" + owner.firstCount() + "§7 to §f"
                    + owner.latestCount() + " §7attributed threads, increasing on §e"
                    + owner.positiveSteps() + " §7separate checks with new threads on §e"
                    + owner.newThreadSteps() + "§7 of them.");
        }
    }

    private static String memoryPressureLine(AnalysisReport report, double latestMiB, double maxMiB) {
        if (report.maximumHeapBytes() <= 0) {
            return "§7Memory after cleanup: §f" + formatNumber(latestMiB) + " MiB";
        }

        double pressure = report.latestPostGcHeapBytes() / (double) report.maximumHeapBytes();
        String prefix = pressure >= 0.90 ? "§c" : pressure >= 0.80 ? "§e" : "§7";
        return prefix + "Memory after cleanup: §f" + formatNumber(latestMiB)
                + " / " + formatNumber(maxMiB) + " MiB §8(" + formatPercentage(pressure) + ")";
    }

    private static String restartAdviceLine(AnalysisReport report) {
        double pressure = report.maximumHeapBytes() <= 0
                ? 0.0
                : report.latestPostGcHeapBytes() / (double) report.maximumHeapBytes();

        if (pressure >= 0.90) {
            return "§e3. §cRestart Minecraft soon; memory is close to the configured limit.";
        }
        if (pressure >= 0.80) {
            return "§e3. §fRestart Minecraft if memory keeps rising or you start getting stutters.";
        }
        return "§e3. §7No action is needed.";
    }

    private static boolean isNsm(ModIdentity mod) {
        return mod != null && "nsm".equalsIgnoreCase(mod.id());
    }

    private enum Confidence {
        LOW("Low"),
        MODERATE("Moderate"),
        HIGH("High");

        private final String label;

        Confidence(String label) {
            this.label = label;
        }
    }

    private record Attribution(ModIdentity mod, Confidence confidence) {
        private static final Attribution NONE = new Attribution(null, Confidence.LOW);

        private boolean actionable() {
            return mod != null && confidence != Confidence.LOW;
        }
    }

    private static void appendLifecycleWarning(List<String> lines, List<LifecycleLeakDetector.Suspect> lifecycleSuspects) {
        if (lifecycleSuspects.isEmpty()) return;

        LifecycleLeakDetector.Suspect first = lifecycleSuspects.getFirst();
        lines.add(
                "§cWarning: §fOld " + friendlyObjectName(first.kind())
                        + " data is still in memory after §e"
                        + first.completedMajorCollections()
                        + " §7cleanups."
        );
        lines.add("§eRun §f/nsm memleak diagnose §eto see what was kept.");
    }

    private static String friendlyObjectName(String kind) {
        if (kind == null) return "game";
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "world" -> "world";
            case "player" -> "player";
            case "screen" -> "menu/screen";
            default -> "game";
        };
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

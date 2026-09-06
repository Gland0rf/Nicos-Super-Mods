package com.nico.client.memleak;

import net.minecraft.client.multiplayer.chat.report.Report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class ReportWriter {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private ReportWriter() { }

    public static Path write(
            Path directory,
            AnalysisReport report,
            List<HeapSnapshot> history,
            Collection<ModIdentity> mods,
            boolean allocationSamplerRunning,
            String allocationSamplerFailure,
            List<ActivityTimeline.Event> activityTimeline,
            List<LifecycleLeakDetector.Suspect> lifecycleSuspects,
            ThreadLeakDetector.Report threadReport
    ) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve("nsm-memleak-" + FILE_TIME.format(report.windowEnd()) + ".json");
        Files.writeString(target, toJson(
                report,
                history,
                mods,
                allocationSamplerRunning,
                allocationSamplerFailure,
                activityTimeline,
                lifecycleSuspects,
                threadReport
        ));
        return target;
    }

    private static String toJson(
            AnalysisReport report,
            List<HeapSnapshot> history,
            Collection<ModIdentity> mods,
            boolean samplerRunning,
            String samplerFailure,
            List<ActivityTimeline.Event> activityTimeline,
            List<LifecycleLeakDetector.Suspect> lifecycleSuspects,
            ThreadLeakDetector.Report threadReport
    ) {
        StringBuilder json = new StringBuilder(8192);
        json.append("{\n");
        field(json, "formatVersion", "4", false, 1);
        field(json, "disclaimer", "Allocation candidates are correlation signals, not proof that a mod retains the objects.", true, 1);
        field(json, "state", report.state().name(), true, 1);
        field(json, "explanation", report.explanation(), true, 1);
        field(json, "windowStart", report.windowStart().toString(), true, 1);
        field(json, "windowEnd", report.windowEnd().toString(), true, 1);
        field(json, "postGcSamples", Integer.toString(report.postGcSamples()), false, 1);
        field(json, "observedSeconds", Long.toString(report.observedFor().toSeconds()), false, 1);
        field(json, "firstPostGcHeapBytes", Long.toString(report.firstPostGcHeapBytes()), false, 1);
        field(json, "latestPostGcHeapBytes", Long.toString(report.latestPostGcHeapBytes()), false, 1);
        field(json, "maximumHeapBytes", Long.toString(report.maximumHeapBytes()), false, 1);
        field(json, "growthRateBytesPerMinute", number(report.growthRateBytesPerMinute()), false, 1);
        field(json, "rSquared", number(report.rSquared()), false, 1);
        field(json, "monotonicity", number(report.monotonicity()), false, 1);
        field(json, "allocationSamplerRunning", Boolean.toString(samplerRunning), false, 1);
        if (samplerFailure != null) {
            field(json, "allocationSamplerFailure", samplerFailure, true, 1);
        }

        indent(json, 1).append("\"candidates\": [\n");
        for (int i = 0; i < report.candidates().size(); i++) {
            AnalysisReport.Candidate candidate = report.candidates().get(i);
            indent(json, 2).append("{\n");
            field(json, "modId", candidate.mod().id(), true, 3);
            field(json, "name", candidate.mod().name(), true, 3);
            field(json, "version", candidate.mod().version(), true, 3);
            field(json, "supportUrl", candidate.mod().supportUrl(), true, 3);
            field(json, "sampledAllocationBytes", Long.toString(candidate.sampledAllocationBytes()), false, 3);
            lastField(json, "allocationShare", number(candidate.allocationShare()), false, 3);
            indent(json, 2).append('}').append(i + 1 == report.candidates().size() ? "\n" : ",\n");
        }
        indent(json, 1).append("],\n");

        indent(json, 1).append("\"postGcHistory\": [\n");
        for (int i = 0; i < history.size(); i++) {
            HeapSnapshot snapshot = history.get(i);
            indent(json, 2).append("{\n");
            field(json, "time", snapshot.time().toString(), true, 3);
            field(json, "heapBytes", Long.toString(snapshot.postGcHeapBytes()), false, 3);
            field(json, "collector", snapshot.collector(), true, 3);
            lastField(json, "cause", snapshot.cause(), true, 3);
            indent(json, 2).append('}').append(i + 1 == history.size() ? "\n" : ",\n");
        }
        indent(json, 1).append("],\n");

        indent(json, 1).append("\"activityTimeline\": [\n");
        for (int i = 0; i < activityTimeline.size(); i++) {
                ActivityTimeline.Event event = activityTimeline.get(i);
                indent(json, 2).append("{\n");
                field(json, "time", event.time().toString(), true, 3);
                field(json, "type", event.type(), true, 3);
                lastField(json, "description", event.description(), true, 3);
                indent(json, 2).append('}').append(i + 1 == activityTimeline.size() ? "\n" : ",\n");
            }
        indent(json, 1).append("],\n");

        indent(json, 1).append("\"lifecycleSuspects\": [\n");
        for (int i = 0; i < lifecycleSuspects.size(); i++) {
            LifecycleLeakDetector.Suspect suspect = lifecycleSuspects.get(i);
            indent(json, 2).append("{\n");
            field(json, "kind", suspect.kind(), true, 3);
            field(json, "description", suspect.description(), true, 3);
            field(json, "releasedAt", suspect.releasedAt().toString(), true, 3);
            field(json, "completedMajorCollections", Integer.toString(suspect.completedMajorCollections()), false, 3);
            lastField(json, "retainedSeconds", Long.toString(suspect.retainedFor().toSeconds()), false, 3);
            indent(json, 2).append('}').append(i + 1 == lifecycleSuspects.size() ? "\n" : ",\n");
        }
        indent(json, 1).append("],\n");

        indent(json, 1).append("\"threadAnalysis\": {\n");

        field(json, "suspicious", Boolean.toString(threadReport.suspicious()), false, 2);
        field(json, "samples", Integer.toString(threadReport.samples()), false, 2);
        field(json, "observedSeconds", Long.toString(threadReport.observedFor().toSeconds()), false, 2);
        field(json, "firstThreadCount", Integer.toString(threadReport.firstThreadCount()), false, 2);
        field(json, "latestThreadCount", Integer.toString(threadReport.latestThreadCount()), false, 2);
        field(json, "totalGrowth", Integer.toString(threadReport.totalGrowth()), false, 2);
        field(json, "totalPositiveSteps", Integer.toString(threadReport.totalPositiveSteps()), false, 2);
        field(json, "totalNegativeSteps", Integer.toString(threadReport.totalNegativeSteps()), false, 2);
        field(json, "totalNewThreadSteps", Integer.toString(threadReport.totalNewThreadSteps()), false, 2);
        field(json, "totalSustainedGrowth", Boolean.toString(threadReport.totalSustainedGrowth()), false, 2);
        indent(json, 2).append("\"ownerGrowth\": [\n");
        for (int i = 0; i < threadReport.ownerGrowth().size(); i++) {
            ThreadLeakDetector.OwnerGrowth owner = threadReport.ownerGrowth().get(i);
            indent(json, 3).append("{\n");
            field(json, "modId", owner.mod().id(), true, 4);
            field(json, "name", owner.mod().name(), true, 4);
            field(json, "version", owner.mod().version(), true, 4);
            field(json, "supportUrl", owner.mod().supportUrl(), true, 4);
            field(json, "firstCount", Integer.toString(owner.firstCount()), false, 4);
            field(json, "latestCount", Integer.toString(owner.latestCount()), false, 4);
            field(json, "growth", Integer.toString(owner.growth()), false, 4);
            field(json, "positiveSteps", Integer.toString(owner.positiveSteps()), false, 4);
            field(json, "negativeSteps", Integer.toString(owner.negativeSteps()), false, 4);
            field(json, "newThreadSteps", Integer.toString(owner.newThreadSteps()), false, 4);
            field(json, "observedSamples", Integer.toString(owner.observedSamples()), false, 4);
            lastField(json, "sustainedGrowth", Boolean.toString(owner.sustainedGrowth()), false, 4);
            indent(json, 3).append('}').append(i + 1 == threadReport.ownerGrowth().size() ? "\n" : ",\n");
        }
        indent(json, 2).append("]\n");
        indent(json, 1).append("},\n");

        indent(json, 1).append("\"loadedMods\": [\n");
        int index = 0;
        for (ModIdentity mod : mods) {
            indent(json, 2).append("{\n");
            field(json, "id", mod.id(), true, 3);
            field(json, "name", mod.name(), true, 3);
            field(json, "version", mod.version(), true, 3);
            lastField(json, "supportUrl", mod.supportUrl(), true, 3);
            indent(json, 2).append('}').append(++index == mods.size() ? "\n" : ",\n");
        }
        indent(json, 1).append("]\n");
        json.append("}\n");
        return json.toString();
    }

    private static void field(StringBuilder out, String name, String value, boolean quote, int depth) {
        appendField(out, name, value, quote, depth).append(",\n");
    }

    private static void lastField(StringBuilder out, String name, String value, boolean quote, int depth) {
        appendField(out, name, value, quote, depth).append('\n');
    }

    private static StringBuilder appendField(StringBuilder out, String name, String value, boolean quote, int depth) {
        indent(out, depth).append('"').append(escape(name)).append("\": ");
        if (quote) {
            out.append('"').append(escape(value)).append('"');
        } else {
            out.append(value);
        }
        return out;
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6f", value) : "0";
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static StringBuilder indent(StringBuilder out, int depth) {
        return out.append("  ".repeat(depth));
    }
}

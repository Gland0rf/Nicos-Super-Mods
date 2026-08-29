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
            String allocationSamplerFailure
    ) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve("nsm-leakdec-" + FILE_TIME.format(report.windowEnd()) + ".json");
        Files.writeString(target, toJson(report, history, mods, allocationSamplerRunning, allocationSamplerFailure));
        return target;
    }

    private static String toJson(
            AnalysisReport report,
            List<HeapSnapshot> history,
            Collection<ModIdentity> mods,
            boolean samplerRunning,
            String samplerFailure
    ) {
        StringBuilder json = new StringBuilder(8192);
        json.append("{\n");
        field(json, "formatVersion", "1", false, 1);
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
            field(json, "sampledAllocationBytes", Long.toString(candidate.sampledAllocationBytes()), false, 3);
            lastField(json, "allocationShare", number(candidate.allocationShare()), false, 3);
            indent(json, 2).append('}').append(i + 1 == report.candidates().size() ? "\n" : ",\n");
        }
        indent(json, 1).append("],\n");

        indent(json, 1).append("\"postGcHistory\": {\n");
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

        indent(json, 1).append("\"loadedMods\": [\n");
        int index = 0;
        for (ModIdentity mod : mods) {
            indent(json, 2).append("{\n");
            field(json, "id", mod.id(), true, 3);
            field(json, "name", mod.name(), true, 3);
            lastField(json, "version", mod.version(), true, 3);
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
                    if (character == 0x20) {
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

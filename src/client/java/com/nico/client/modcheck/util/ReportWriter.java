package com.nico.client.modcheck.util;

import com.nico.client.modcheck.scan.ScanFinding;
import com.nico.client.modcheck.scan.ScanReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class ReportWriter {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private ReportWriter() { }

    public static Path write(Path gameDirectory, ScanReport report) throws IOException {
        Path directory = gameDirectory.resolve("config").resolve("modguard").resolve("reports");
        Files.createDirectories(directory);

        String fileName = "scan-" + FILE_TIME.format(report.scanTime()) + ".json";
        Path reportFile = directory.resolve(fileName);
        Path temporary = directory.resolve(fileName + ".tmp");
        Files.writeString(temporary, toJson(report), StandardCharsets.UTF_8);
        moveReplacing(temporary, reportFile);

        Path latest = directory.resolve("latest.json");
        Path latestTemporary = directory.resolve("latest.json.tmp");
        Files.copy(reportFile, latestTemporary, StandardCopyOption.REPLACE_EXISTING);
        moveReplacing(latestTemporary, latest);

        report.setReportPath(reportFile);
        return reportFile;
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String toJson(ScanReport report) {
        StringBuilder json = new StringBuilder(4096);
        json.append("{\n")
                .append("  \"scanTime\": ").append(quote(report.scanTime().toString())).append(",\n")
                .append("  \"securityBoundary\": \"post-loader-discovery; other mod code may already have executed\",\n")
                .append("  \"registrySource\": ").append(quote(report.registrySource())).append(",\n")
                .append("  \"registryStatus\": ").append(quote(report.registryStatus())).append(",\n")
                .append("  \"registryGeneratedAt\": ")
                .append(report.registryGeneratedAt() == null ? "null" : quote(report.registryGeneratedAt().toString()))
                .append(",\n")
                .append("  \"summary\": {\n")
                .append("    \"critical\": ").append(report.criticalCount()).append(",\n")
                .append("    \"warnings\": ").append(report.warningCount()).append(",\n")
                .append("    \"verified\": ").append(report.verifiedCount()).append("\n")
                .append("  },\n")
                .append("  \"findings\": [\n");

        for (int i = 0; i < report.findings().size(); i++) {
            ScanFinding finding = report.findings().get(i);
            json.append("    {\n")
                    .append("      \"fileName\": ").append(quote(finding.fileName())).append(",\n")
                    .append("      \"relativePath\": ").append(quote(finding.relativePath())).append(",\n")
                    .append("      \"modId\": ").append(quote(finding.metadata().modId())).append(",\n")
                    .append("      \"name\": ").append(quote(finding.metadata().name())).append(",\n")
                    .append("      \"version\": ").append(quote(finding.metadata().version())).append(",\n")
                    .append("      \"sha512\": ").append(quote(finding.sha512())).append(",\n")
                    .append("      \"status\": ").append(quote(finding.status().name())).append(",\n")
                    .append("      \"severity\": ").append(quote(finding.severity().name())).append(",\n")
                    .append("      \"detail\": ").append(quote(finding.detail())).append(",\n")
                    .append("      \"registryProject\": ").append(quote(finding.registryProject())).append(",\n")
                    .append("      \"registryVersion\": ").append(quote(finding.registryVersion())).append("\n")
                    .append("    }");
            if (i + 1 < report.findings().size()) {
                json.append(',');
            }
            json.append('\n');
        }

        json.append("  ]\n}");
        return json.toString();
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        escaped.append('"');
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }
}

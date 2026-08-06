package com.nico.client.modcheck.scan;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class ScanReport {
    private final Instant scanTime;
    private final String registrySource;
    private final String registryStatus;
    private final Instant registryGeneratedAt;
    private final List<ScanFinding> findings;
    private Path reportPath;

    public ScanReport(
            Instant scanTime,
            String registrySource,
            String registryStatus,
            Instant registryGeneratedAt,
            List<ScanFinding> findings
    ) {
        this.scanTime = scanTime;
        this.registrySource = registrySource;
        this.registryStatus = registryStatus;
        this.registryGeneratedAt = registryGeneratedAt;
        this.findings = List.copyOf(findings);
    }

    public Instant scanTime() {
        return scanTime;
    }

    public String registrySource() {
        return registrySource;
    }

    public String registryStatus() {
        return registryStatus;
    }

    public Instant registryGeneratedAt() {
        return registryGeneratedAt;
    }

    public List<ScanFinding> findings() {
        return findings;
    }

    public long criticalCount() {
        return findings.stream().filter(f -> f.severity() == FindingSeverity.CRITICAL).count();
    }

    public long warningCount() {
        return findings.stream().filter(f -> f.severity() == FindingSeverity.WARNING).count();
    }

    public long verifiedCount() {
        return findings.stream().filter(f -> f.status() == FindingStatus.VERIFIED_OFFICIAL_RELEASE).count();
    }

    public boolean shouldShowWarning() {
        return criticalCount() > 0 || warningCount() > 0;
    }

    public Path reportPath() {
        return reportPath;
    }

    public void setReportPath(Path reportPath) {
        this.reportPath = reportPath;
    }

    public String headline() {
        if (criticalCount() > 0) {
            return "Critical mod verification warning";
        }
        if (warningCount() > 0) {
            return "Unverified mods detected";
        }
        return "All scanned mods verified";
    }

    public String toHumanReadable() {
        StringBuilder output = new StringBuilder();
        output.append("NSM scan\n")
                .append("Time: ").append(scanTime).append('\n')
                .append("Registry: ").append(registryStatus).append('\n')
                .append("Critical: ").append(criticalCount()).append('\n')
                .append("Warnings: ").append(warningCount()).append('\n')
                .append("Verified: ").append(verifiedCount()).append("\n\n");

        for (ScanFinding finding : findings) {
            if (finding.severity() == FindingSeverity.INFO) {
                continue;
            }
            output.append('[').append(finding.severity()).append(']')
                    .append(finding.fileName()).append(" - ")
                    .append(finding.status()).append('\n')
                    .append(" ").append(finding.detail()).append('\n')
                    .append("  SHA-512: ").append(finding.sha512()).append("\n\n");
        }

        output.append("Important: another Fabric mod may already have executed before this warning appeared.\n");
        return output.toString();
    }

    public boolean hasActionableFindings() {
        return findings.stream().anyMatch(finding ->
                finding.severity() == FindingSeverity.CRITICAL ||
                finding.severity() == FindingSeverity.WARNING
        );
    }
}

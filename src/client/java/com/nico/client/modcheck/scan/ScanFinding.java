package com.nico.client.modcheck.scan;

public record ScanFinding(
        String fileName,
        String relativePath,
        JarMetadata metadata,
        String sha512,
        FindingStatus status,
        FindingSeverity severity,
        String detail,
        String registryProject,
        String registryVersion
) {
    public boolean isIgnorableUnknown() {
        return severity == FindingSeverity.WARNING
                && status == FindingStatus.UNKNOWN_MOD || status == FindingStatus.UNKNOWN_JAR
                && sha512 != null
                && sha512.matches("[0-9a-fA-F]{128}");
    }
}

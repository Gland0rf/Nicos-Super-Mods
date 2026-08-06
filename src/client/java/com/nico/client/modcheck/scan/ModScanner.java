package com.nico.client.modcheck.scan;

import com.nico.client.modcheck.config.IgnoredUnknownMods;
import com.nico.client.modcheck.config.ModCheckConfig;
import com.nico.client.modcheck.registry.RegistryFetchResult;
import com.nico.client.modcheck.registry.RegistryProject;
import com.nico.client.modcheck.registry.RegistryRelease;
import com.nico.client.modcheck.registry.TrustRegistry;
import com.nico.client.modcheck.util.Hashing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ModScanner {
    private final JarMetadataReader metadataReader = new JarMetadataReader();

    public ScanReport scan(
            Path gameDirectory,
            Path modsDirectory,
            RegistryFetchResult registryFetchResult,
            Exception registryFailure
    ) {
        List<ScanFinding> findings = new ArrayList<>();
        TrustRegistry registry = registryFetchResult == null ? null : registryFetchResult.registry();

        if (registryFailure != null) {
            findings.add(new ScanFinding(
                    "registry.json",
                    "remote registry",
                    new JarMetadata("", "Remote trust registry", ""),
                    "",
                    FindingStatus.REGISTRY_UNAVAILABLE,
                    FindingSeverity.WARNING,
                    sanitizeException(registryFailure),
                    "",
                    ""
            ));
        }

        List<Path> jars;
        try (var stream = Files.list(modsDirectory)) {
            jars = stream
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
        } catch (IOException exception) {
            findings.add(new ScanFinding(
                    modsDirectory.getFileName().toString(),
                    safeRelative(gameDirectory, modsDirectory),
                    JarMetadata.unknown(modsDirectory.getFileName().toString()),
                    "",
                    FindingStatus.SCAN_ERROR,
                    FindingSeverity.CRITICAL,
                    "Could not list the mods directory: " + sanitizeException(exception),
                    "",
                    ""
            ));
            return report(registryFetchResult, registryFailure, findings);
        }

        for (Path jar : jars) {
            scanJar(gameDirectory, jar, registry, findings);
        }

        return report(registryFetchResult, registryFailure, findings);
    }

    private void scanJar(
            Path gameDirectory,
            Path jar,
            TrustRegistry registry,
            List<ScanFinding> findings
    ) {
        String fileName = jar.getFileName().toString();
        String relativePath = safeRelative(gameDirectory, jar);

        if (Files.isSymbolicLink(jar)) {
            findings.add(new ScanFinding(
                    fileName,
                    relativePath,
                    JarMetadata.unknown(fileName),
                    "",
                    FindingStatus.SYMBOLIC_LINK,
                    FindingSeverity.WARNING,
                    "The mod file is a symbolic link. Its target can change independently of this path.",
                    "",
                    ""
            ));
        }

        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(jar)) {
            findings.add(new ScanFinding(
                    fileName,
                    relativePath,
                    JarMetadata.unknown(fileName),
                    "",
                    FindingStatus.SCAN_ERROR,
                    FindingSeverity.WARNING,
                    "The path is not a regular readable file.",
                    "",
                    ""
            ));
            return;
        }

        JarMetadata metadata;
        String sha512;
        try {
            metadata = metadataReader.read(jar);
            sha512 = Hashing.sha512(jar);
        } catch (Exception exception) {
            findings.add(new ScanFinding(
                    fileName,
                    relativePath,
                    JarMetadata.unknown(fileName),
                    "",
                    FindingStatus.SCAN_ERROR,
                    FindingSeverity.CRITICAL,
                    "Could not inspect this JAR: " + sanitizeException(exception),
                    "",
                    ""
            ));
            return;
        }

        if (registry == null) {
            findings.add(new ScanFinding(
                    fileName,
                    relativePath,
                    metadata,
                    sha512,
                    FindingStatus.REGISTRY_UNAVAILABLE,
                    FindingSeverity.WARNING,
                    "The remote trust registry could not be verified, so this file could not be classified.",
                    "",
                    ""
            ));
            return;
        }

        RegistryRelease release = registry.findReleaseByHash(sha512);
        if (release != null) {
            RegistryProject claimedProject = registry.findProjectByModId(metadata.modId());
            boolean metadataMatches = claimedProject != null
                    && claimedProject.primaryModId().equalsIgnoreCase(release.primaryModId());

            if (!metadataMatches) {
                findings.add(new ScanFinding(
                        fileName,
                        relativePath,
                        metadata,
                        sha512,
                        FindingStatus.VERIFIED_HASH_METADATA_MISMATCH,
                        FindingSeverity.CRITICAL,
                        "The file hash is registered to " + release.projectName()
                                + " but its fabric.mod.json claims mod ID '" + metadata.modId() + "'.",
                        release.projectName(),
                        release.version()
                ));
                return;
            }

            findings.add(new ScanFinding(
                    fileName,
                    relativePath,
                    metadata,
                    sha512,
                    FindingStatus.VERIFIED_OFFICIAL_RELEASE,
                    FindingSeverity.INFO,
                    "Exact SHA-512 match for the trusted release.",
                    release.projectName(),
                    release.version()
            ));
            return;
        }

        RegistryProject claimedProject = registry.findProjectByModId(metadata.modId());
        if (claimedProject != null) {
            findings.add(new ScanFinding(
                    fileName,
                    relativePath,
                    metadata,
                    sha512,
                    FindingStatus.OFFICIAL_PROJECT_HASH_MISMATCH,
                    FindingSeverity.CRITICAL,
                    "This JAR claims to be " + claimedProject.name()
                            + " but its SHA-512 is not listed as a trusted release.",
                    claimedProject.name(),
                    ""
            ));
            return;
        }

        boolean previouslyIgnored = IgnoredUnknownMods.contains(sha512);

        FindingSeverity severity;

        if (previouslyIgnored) severity = FindingSeverity.INFO;
        else severity = ModCheckConfig.WARN_ON_UNKNOWN_MODS ? FindingSeverity.WARNING : FindingSeverity.INFO;

        FindingStatus status = metadata.modId().isBlank()
                ? FindingStatus.UNKNOWN_JAR
                : FindingStatus.UNKNOWN_MOD;

        String detail;

        if (previouslyIgnored) detail = "This exact unknown JAR was previously acknowledged.";
        else if (metadata.modId().isBlank()) detail = "The JAR has no readable fabric.mod.json.";
        else detail = "The mod ID is not present in the trust registry.";

        findings.add(new ScanFinding(
                fileName,
                relativePath,
                metadata,
                sha512,
                status,
                severity,
                detail,
                "",
                ""
        ));
    }

    private static ScanReport report(
            RegistryFetchResult registryFetchResult,
            Exception registryFailure,
            List<ScanFinding> findings
    ) {
        return new ScanReport(
                Instant.now(),
                registryFetchResult == null ? ModCheckConfig.REGISTRY_URL : registryFetchResult.sourceUrl(),
                registryFailure == null
                        ? (registryFetchResult.signatureVerified() ? "signature verified" : "unsigned development mode")
                        : "unavailable: " + sanitizeException(registryFailure),
                registryFetchResult == null ? null : registryFetchResult.registry().generatedAt(),
                findings
        );
    }

    private static Path resolveSafely(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ignored) {
            return path.toAbsolutePath().normalize();
        }
    }

    private static String safeRelative(Path gameDirectory, Path path) {
        Path game = resolveSafely(gameDirectory);
        Path target = resolveSafely(path);
        try {
            return game.relativize(target).toString();
        } catch (IllegalArgumentException ignored) {
            return target.toString();
        }
    }

    private static String sanitizeException(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": "
                + message.replace('\n', ' ').replace('\r', ' ');
    }
}

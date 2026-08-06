package com.nico.client.modcheck.registry;

import com.nico.client.modcheck.json.MiniJson;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

public final class TrustRegistry {
    private static final Pattern SHA_512_PATTERN = Pattern.compile("[0-9a-f]{128}");

    private final int schemaVersion;
    private final Instant generatedAt;
    private final Map<String, RegistryProject> projectsByModId;
    private final Map<String, RegistryRelease> releasesByHash;

    private TrustRegistry(
            int schemaVersion,
            Instant generatedAt,
            Map<String, RegistryProject> projectsByModId,
            Map<String, RegistryRelease> releasesByHash
    ) {
        this.schemaVersion = schemaVersion;
        this.generatedAt = generatedAt;
        this.projectsByModId = Map.copyOf(projectsByModId);
        this.releasesByHash = Map.copyOf(releasesByHash);
    }

    public static TrustRegistry parse(byte[] bytes) {
        String json = new String(bytes, StandardCharsets.UTF_8);
        Map<String, Object> root = object(MiniJson.parse(json), "root");

        Object rawSchemaVersion = root.get("schemaVersion");

        System.out.println(
                "schemaVersion = " + rawSchemaVersion
                        + ", type = "
                        + (rawSchemaVersion == null
                        ? "null"
                        : rawSchemaVersion.getClass().getName())
        );

        int schemaVersion = integer(root.get("schemaVersion"), "schemaVersion");
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported registry schemaVersion: " + schemaVersion);
        }

        Instant generatedAt = parseInstant(string(root.get("generatedAt"), "generatedAt"));
        List<Object> rawProjects = array(root.get("projects"), "projects");

        Map<String, RegistryProject> projectsByModId = new LinkedHashMap<>();
        Map<String, RegistryRelease> releasesByHash = new HashMap<>();
        Set<String> seenHashes = new HashSet<>();
        Set<String> seenModIds = new HashSet<>();

        for (int projectIndex = 0; projectIndex < rawProjects.size(); projectIndex++) {
            Map<String, Object> rawProject = object(rawProjects.get(projectIndex), "projects[" + projectIndex + "]");
            String name = string(rawProject.get("name"), "projects[" + projectIndex + "]");
            String projectUrl = optionalString(rawProject.get("projectUrl"));

            List<Object> rawModIds = array(rawProject.get("modIds"), "projects[" + projectIndex + "].modIds");
            if (rawModIds.isEmpty()) {
                throw new IllegalArgumentException("Project" + name + " has no modIds");
            }

            List<String> modIds = new ArrayList<>();
            for (int modIdIndex = 0; modIdIndex < rawModIds.size(); modIdIndex++) {
                String modId = string(rawModIds.get(modIdIndex), "modIds[" + modIdIndex + "]")
                        .toLowerCase(Locale.ROOT);
                if (!seenModIds.add(modId)) {
                    throw new IllegalArgumentException("Duplicate modId in registry: " + modId);
                }
                modIds.add(modId);
            }

            List<Object> rawReleases = array(rawProject.get("releases"), "projects[" + projectIndex + "].releases");
            List<RegistryRelease> releases = new ArrayList<>();
            for (int releaseIndex = 0; releaseIndex < rawReleases.size(); releaseIndex++) {
                Map<String, Object> rawRelease = object(
                        rawReleases.get(releaseIndex),
                        "projects[" + projectIndex + "].releases[" + releaseIndex + "]"
                );
                String version = string(rawRelease.get("version"), "release.version");
                String fileName = optionalString(rawRelease.get("fileName"));
                String sha512 = string(rawRelease.get("sha512"), "release.sha512")
                        .toLowerCase(Locale.ROOT);

                if (!SHA_512_PATTERN.matcher(sha512).matches()) {
                    throw new IllegalArgumentException("Invalid SHA-512 for " + name + " " + version);
                }
                if (!seenHashes.add(sha512)) {
                    throw new IllegalArgumentException("Duplicate SHA-512 in registry: " + sha512);
                }

                RegistryRelease release = new RegistryRelease(
                        name,
                        modIds.getFirst(),
                        version,
                        fileName,
                        sha512,
                        projectUrl
                );
                releases.add(release);
                releasesByHash.put(sha512, release);
            }

            RegistryProject project = new RegistryProject(
                    name,
                    List.copyOf(modIds),
                    projectUrl,
                    List.copyOf(releases)
            );
            for (String modId : modIds) {
                projectsByModId.put(modId, project);
            }
        }

        return new TrustRegistry(schemaVersion, generatedAt, projectsByModId, releasesByHash);
    }

    public RegistryRelease findReleaseByHash(String sha512) {
        return releasesByHash.get(sha512.toLowerCase(Locale.ROOT));
    }

    public RegistryProject findProjectByModId(String modId) {
        if (modId == null) {
            return null;
        }
        return projectsByModId.get(modId.toLowerCase(Locale.ROOT));
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public Instant generatedAt() {
        return generatedAt;
    }

    public int projectCount() {
        return new HashSet<>(projectsByModId.values()).size();
    }

    public int releaseCount() {
        return releasesByHash.size();
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Invalid generatedAt timestamp", exception);
        }
    }

    private static Map<String, Object> object(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    private static List<Object> array(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(path + " must be an array");
        }
        return (List<Object>) list;
    }

    private static String string(Object value, String path) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(path + " must be a non-empty string");
        }
        return text;
    }

    private static String optionalString(Object value) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Expected a string");
        }
        return text;
    }

    private static int integer(Object value, String path) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(path + " must be a number");
        }
        return number.intValue();
    }
}

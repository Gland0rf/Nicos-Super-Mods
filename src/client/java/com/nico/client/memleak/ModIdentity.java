package com.nico.client.memleak;

public record ModIdentity(
        String id,
        String name,
        String version,
        String issuesUrl,
        String homepageUrl,
        String sourcesUrl
) {
    public ModIdentity(String id, String name, String version) {
        this(id, name, version, "", "", "");
    }

    public ModIdentity {
        id = safe(id);
        name = safe(name);
        version = safe(version);
        issuesUrl = safe(issuesUrl);
        homepageUrl = safe(homepageUrl);
        sourcesUrl = safe(sourcesUrl);
    }

    public String displayName() {
        return name.equals(id) ? name : name + " (" + id + ")";
    }

    public String displayNameWithVersion() {
        if (version.isBlank()) {
            return displayName();
        }
        return displayName() + " " + version;
    }

    public String supportUrl() {
        if (!issuesUrl.isBlank()) return issuesUrl;
        if (!sourcesUrl.isBlank()) return sourcesUrl;
        return homepageUrl;
    }

    public static boolean isInfrastructureId(String modId) {
        if (modId == null) return false;

        return modId.equals("minecraft")
                || modId.equals("java")
                || modId.equals("fabricloader")
                || modId.equals("fabric-api")
                || modId.startsWith("fabric-")
                || modId.startsWith("fabric_");
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}

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

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}

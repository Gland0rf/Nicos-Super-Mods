package com.nico.client.modcheck.registry;

import java.util.List;

public record RegistryProject(
        String name,
        List<String> modIds,
        String projectUrl,
        List<RegistryRelease> releases
) {
    public String primaryModId() {
        return modIds.getFirst();
    }
}

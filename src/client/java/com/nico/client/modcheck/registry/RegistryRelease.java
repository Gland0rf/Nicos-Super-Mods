package com.nico.client.modcheck.registry;

public record RegistryRelease(
        String projectName,
        String primaryModId,
        String version,
        String fileName,
        String sha512,
        String projectUrl
) {
}

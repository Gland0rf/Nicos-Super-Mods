package com.nico.client.modcheck.scan;

public record JarMetadata (
        String modId,
        String name,
        String version
) {
    public static JarMetadata unknown(String fileName) {
        return new JarMetadata("", fileName, "");
    }
}
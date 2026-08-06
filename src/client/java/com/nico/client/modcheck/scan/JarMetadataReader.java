package com.nico.client.modcheck.scan;

import com.nico.client.modcheck.json.MiniJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class JarMetadataReader {
    private static final int MAX_METADATA_BYTES = 1024 * 1024;

    public JarMetadata read(Path jar) throws IOException {
        try (ZipFile zipFile = new ZipFile(jar.toFile())) {
            ZipEntry metadataEntry = zipFile.getEntry("fabric.mod.json");
            if (metadataEntry == null) {
                return JarMetadata.unknown(jar.getFileName().toString());
            }
            if (metadataEntry.getSize() > MAX_METADATA_BYTES) {
                throw new IOException("fabric.mod.json exceeds size limit");
            }

            byte[] bytes;
            try (InputStream input = zipFile.getInputStream(metadataEntry)) {
                bytes = input.readNBytes(MAX_METADATA_BYTES + 1);
            }
            if (bytes.length > MAX_METADATA_BYTES) {
                throw new IOException("fabric.mod.json exceeds size limit");
            }

            Object parsed = MiniJson.parse(new String(bytes, StandardCharsets.UTF_8));
            Map<String, Object> object = selectMetadataObject(parsed);
            String id = text(object.get("id"));
            String name = text(object.get("name"));
            String version = text(object.get("version"));

            return new JarMetadata(
                    id,
                    name.isBlank() ? jar.getFileName().toString() : name,
                    version
            );
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid fabric.mod.json " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> selectMetadataObject(Object parsed) {
        if (parsed instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (parsed instanceof List<?> list && !list.isEmpty() && list.getFirst() instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("fabric.mod.json root must be an object");
    }

    private static String text(Object value) {
        return value instanceof String string ? string : "";
    }
}

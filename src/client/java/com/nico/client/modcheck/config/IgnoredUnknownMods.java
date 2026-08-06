package com.nico.client.modcheck.config;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;

public final class IgnoredUnknownMods {

    private static final Logger LOGGER = LoggerFactory.getLogger("NSM ModCheck");

    private static final Pattern SHA512_PATTERN = Pattern.compile("[0-9a-f]{128}");

    private static final Path FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("nicos_super_mods")
            .resolve("modcheck-ignored-unknown-hashes.txt");

    private static final Set<String> HASHES = new HashSet<>();

    private static boolean loaded;

    private IgnoredUnknownMods() { }

    public static synchronized boolean contains(String sha512) {
        loadIfNecessary();

        String normalized = normalize(sha512);

        return normalized != null && HASHES.contains(normalized);
    }

    public static synchronized int addAll(Collection<String> hashes) throws IOException {
        loadIfNecessary();

        int previousSize = HASHES.size();

        for (String hash : hashes) {
            String normalized = normalize(hash);

            if (normalized != null) {
                HASHES.add(normalized);
            }
        }

        if (HASHES.size() != previousSize) {
            save();
        }

        return HASHES.size() - previousSize;
    }

    private static void loadIfNecessary() {
        if (loaded) return;

        loaded = true;

        if (!Files.isRegularFile(FILE)) return;

        try {
            for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                String normalized = normalize(line);

                if (normalized != null) {
                    HASHES.add(normalized);
                }
            }
        } catch (IOException e) {
            LOGGER.error(
                    "Could not load ignored ModCheck hashes",
                    e
            );
        }
    }

    private static void save() throws IOException {
        Files.createDirectories(FILE.getParent());

        Path temporaryFile = FILE.resolveSibling(FILE.getFileName() + ".tmp");

        Files.write(
                temporaryFile,
                new TreeSet<>(HASHES),
                StandardCharsets.UTF_8
        );

        try {
            Files.move(
                    temporaryFile,
                    FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException atomicMoveFailure) {
            Files.move(
                    temporaryFile,
                    FILE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static String normalize(String value) {
        if (value == null) return null;

        String normalized = value.trim().toLowerCase(Locale.ROOT);

        if (!SHA512_PATTERN.matcher(normalized).matches()) return null;

        return normalized;
    }

}

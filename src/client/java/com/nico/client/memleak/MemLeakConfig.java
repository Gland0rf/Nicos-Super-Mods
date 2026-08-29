package com.nico.client.memleak;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public record MemLeakConfig(
        Duration window,
        Duration minimumObservation,
        int minimumSamples,
        long minimumGrowthBytes,
        double minimumGrowthRateBytesPerMinute,
        double minimumRSquared,
        double minimumMonotonicity,
        Duration alertCooldown,
        int topCandidates,
        int maximumStackFrames
) {
    private static final long MIB = 1024L * 1024L;

    public static MemLeakConfig defaults() {
        return new MemLeakConfig(
                Duration.ofMinutes(30),
                Duration.ofMinutes(10),
                6,
                128L * MIB,
                8.0 * MIB,
                0.65,
                0.70,
                Duration.ofMinutes(20),
                5,
                24
        );
    }

    public static MemLeakConfig load(Path path) {
        MemLeakConfig defaults = defaults();
        Properties properties = new Properties();
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                properties.load(reader);
            } catch (IOException ignored) {
                return defaults;
            }
        }

        MemLeakConfig loaded = new MemLeakConfig(
                Duration.ofMinutes(readLong(properties, "windowMinutes", defaults.window.toMinutes(), 10, 180)),
                Duration.ofMinutes(readLong(properties, "minimumObservationMinutes", defaults.minimumObservation.toMinutes(), 2, 60)),
                (int) readLong(properties, "minimumSamples", defaults.minimumSamples, 3, 50),
                readLong(properties, "minimumGrowthMiB", defaults.minimumGrowthBytes / MIB, 32, 4096) * MIB,
                readDouble(properties, "minimumGrowthMiBPerMinute", defaults.minimumGrowthRateBytesPerMinute / MIB, 1, 512) * MIB,
                readDouble(properties, "minimumRSquared", defaults.minimumRSquared, 0, 1),
                readDouble(properties, "minimumMonotonicity", defaults.minimumMonotonicity, 0, 1),
                Duration.ofMinutes(readLong(properties, "alertCooldownMinutes", defaults.alertCooldown.toMinutes(), 1, 240)),
                (int) readLong(properties, "topCandidates", defaults.topCandidates, 1, 15),
                (int) readLong(properties, "maximumStackFrames", defaults.maximumStackFrames, 4, 128)
        );

        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                Properties normalized = new Properties();
                normalized.setProperty("windowMinutes", Long.toString(loaded.window.toMinutes()));
                normalized.setProperty("minimumObservationMinutes", Long.toString(loaded.minimumObservation.toMinutes()));
                normalized.setProperty("minimumSamples", Integer.toString(loaded.minimumSamples));
                normalized.setProperty("minimumGrowthMiB", Long.toString(loaded.minimumGrowthBytes / MIB));
                normalized.setProperty("minimumGrowthMiBPerMinute", Double.toString(loaded.minimumGrowthRateBytesPerMinute / MIB));
                normalized.setProperty("minimumRSquared", Double.toString(loaded.minimumRSquared));
                normalized.setProperty("minimumMonotonicity", Double.toString(loaded.minimumMonotonicity));
                normalized.setProperty("alertCooldownMinutes", Long.toString(loaded.alertCooldown.toMinutes()));
                normalized.setProperty("topCandidates", Integer.toString(loaded.topCandidates));
                normalized.setProperty("maximumStackFrames", Integer.toString(loaded.maximumStackFrames));
                normalized.store(writer, "NSM MemLeak configuration");
            }
        } catch (IOException ignored) {
            // Monitoring can continue with an in-memory configuration.
        }

        return loaded;
    }

    private static long readLong(Properties properties, String key, long fallback, long min, long max) {
        try {
            return Math.clamp(Long.parseLong(properties.getProperty(key, Long.toString(fallback))), min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double readDouble(Properties properties, String key, double fallback, double min, double max) {
        try {
            return Math.clamp(Double.parseDouble(properties.getProperty(key, Double.toString(fallback))), min, max);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}

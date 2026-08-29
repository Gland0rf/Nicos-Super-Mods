package com.nico.client.memleak;

import com.nico.client.configuration.NsmConfigManager;
import com.nico.client.configuration.category.CategoryOther;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public class MemLeakFeature {
    private static final long MIB = 1024L * 1024L;

    private static volatile MemLeakService service;
    private static boolean shutdownHookRegistered;

    private MemLeakFeature() {
    }

    public static synchronized void initialize() {
        if (service != null) {
            return;
        }

        CategoryOther.MemLeak settings =
                NsmConfigManager.getConfig().other.memLeak;

        if (!settings.enabled) {
            return;
        }

        int observationMinutes = Math.min(
                settings.minimumObservationMinutes,
                settings.windowMinutes
        );

        MemLeakConfig config = new MemLeakConfig(
                Duration.ofMinutes(settings.windowMinutes),
                Duration.ofMinutes(observationMinutes),
                settings.minimumSamples,
                settings.minimumGrowthMiB * MIB,
                settings.minimumGrowthMiBPerMinute * MIB,
                0.65,
                0.70,
                Duration.ofMinutes(20),
                5,
                24
        );

        Path reportDirectory = FabricLoader.getInstance()
                .getGameDir()
                .resolve("nsm-memleak-reports");

        service = new MemLeakService(
                config,
                reportDirectory,
                MemLeakFeature::sendAutomaticAlert
        );

        service.start();

        if (!shutdownHookRegistered) {
            shutdownHookRegistered = true;

            Runtime.getRuntime().addShutdownHook(
                    Thread.ofPlatform()
                            .name("NSM-MemLeak-Shutdown")
                            .unstarted(MemLeakFeature::shutdown)
            );
        }

        System.out.println("[NSM] MemLeak monitor initialized");
    }

    public static boolean isRunning() {
        return service != null;
    }

    public static List<String> statusLines() {
        MemLeakService current = service;

        if (current == null) {
            return List.of(
                    "§c[NSM MemLeak] The detector is disabled.",
                    "§7Enable it in §e/nsmconfig §7and restart Minecraft."
            );
        }

        return current.statusLines();
    }

    public static List<String> candidateLines() {
        MemLeakService current = service;

        if (current == null) {
            return List.of(
                    "§c[NSM MemLeak] The detector is disabled."
            );
        }

        return current.candidateLines();
    }

    public static List<String> modIndexLines() {
        MemLeakService current = service;

        if (current == null) {
            return List.of(
                    "§c[NSM MemLeak] The detector is disabled."
            );
        }

        return current.modIndexLines();
    }

    public static boolean reset() {
        MemLeakService current = service;

        if (current == null) {
            return false;
        }

        current.reset();
        return true;
    }

    public static Path exportReport() throws IOException {
        MemLeakService current = service;

        if (current == null) {
            return null;
        }

        return current.exportReport();
    }

    public static synchronized void shutdown() {
        MemLeakService current = service;
        service = null;

        if (current != null) {
            try {
                current.close();
            } catch (Exception exception) {
                System.err.println("[NSM] Failed to stop MemLeak monitor");
                exception.printStackTrace();
            }
        }
    }

    private static void sendAutomaticAlert(String message) {
        if (!NsmConfigManager.getConfig().other.memLeak.chatAlerts) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();

        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.sendSystemMessage(
                        Component.literal(message)
                );
            }
        });
    }
}

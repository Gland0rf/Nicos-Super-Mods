package com.nico.client.memleak;

import com.nico.client.configuration.NsmConfigManager;
import com.nico.client.configuration.category.CategoryOther;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class MemLeakFeature {
    private static final long MIB = 1024L * 1024L;

    private static volatile MemLeakService service;
    private static boolean shutdownHookRegistered;
    private static boolean clientTickHookRegistered;
    private static boolean lastTickHadWorld;
    private static WeakReference<Object> lastWorld = new WeakReference<>(null);

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

        if (!clientTickHookRegistered) {
            clientTickHookRegistered = true;
            ClientTickEvents.END_CLIENT_TICK.register(MemLeakFeature::onClientTick);
        }

        if (!shutdownHookRegistered) {
            shutdownHookRegistered = true;

            Runtime.getRuntime().addShutdownHook(
                    Thread.ofPlatform()
                            .name("NSM-MemLeak-Shutdown")
                            .unstarted(MemLeakFeature::shutdown)
            );
        }

        System.out.println("[NSM] Memory monitor initialized");
    }

    private static void onClientTick(Minecraft minecraft) {
        Object world = minecraft.level;
        Object previousWorld = lastWorld.get();
        boolean changedAwayFromExistingWorld = lastTickHadWorld
                && (world == null || previousWorld != world);

        if (changedAwayFromExistingWorld && NsmConfigManager.getConfig().other.memLeak.autoCleanupTransientData) {
            NsmTransientCleanup.Result result = NsmTransientCleanup.cleanupWorldState();
            MemLeakService current = service;
            if (current != null) {
                current.markActivity("nsm-cleanup", result.successful()
                        ? "Cleaned temporary NSM world data"
                        : "Cleaned temporary NSM world data with some failures");
            }
        }

        lastTickHadWorld = world != null;
        lastWorld = new WeakReference<>(world);

        MemLeakService current = service;
        if (current != null) {
            current.observeClientState(minecraft.level, minecraft.player, minecraft.screen);
        }
    }

    public static boolean isRunning() {
        return service != null;
    }

    public static List<String> statusLines() {
        MemLeakService current = service;

        if (current == null) {
            return List.of(
                    "§c[NSM Memory Check] The memory checker is disabled.",
                    "§7Enable it in §e/nsmconfig §7and restart Minecraft."
            );
        }

        return current.statusLines();
    }

    public static List<String> diagnosisLines() {
        MemLeakService current = service;

        if (current == null) {
            return List.of(
                    "§c[NSM Memory Check] The memory checker is disabled."
            );
        }

        return current.diagnosisLines();
    }

    /** Backwards-compatible name for the old /suspects command. */
    public static List<String> candidateLines() {
        return diagnosisLines();
    }

    public static List<String> modIndexLines() {
        MemLeakService current = service;

        if (current == null) {
            return List.of(
                    "§c[NSM Memory Check] The memory checker is disabled."
            );
        }

        return current.modIndexLines();
    }

    public static List<String> cleanupLines() {
        NsmTransientCleanup.Result result = NsmTransientCleanup.cleanupWorldState();

        MemLeakService current = service;
        if (current != null) {
            current.markActivity("nsm-cleanup", "Manually cleaned temporary NSM data");
        }

        List<String> lines = new ArrayList<>();
        lines.add("§a[NSM Memory Check] §fTemporary NSM data was cleaned up.");
        lines.add("§7Reset §e" + result.cleanedSystems().size() + " §7temporary systems.");
        lines.add("§8Wiki caches, PBs, routes, layouts, and settings were kept.");
        lines.add("§8Current dungeon tracking was reset and will rebuild as needed.");

        if (!result.failedSystems().isEmpty()) {
            lines.add("§cSome cleanup steps failed: §f" + String.join(", ", result.failedSystems()));
            lines.add("§7Check latest.log for details.");
        }

        return List.copyOf(lines);
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

    public static List<String> reportAdviceLines() {
        MemLeakService current = service;
        return current == null ? List.of() : current.reportAdviceLines();
    }

    /** Optional context marker for NSM features; mod attribution does not depend on this. */
    public static void markActivity(String type, String description) {
        MemLeakService current = service;
        if (current != null) {
            current.markActivity(type, description);
        }
    }

    public static synchronized void shutdown() {
        MemLeakService current = service;
        service = null;
        lastTickHadWorld = false;
        lastWorld = new WeakReference<>(null);

        if (current != null) {
            try {
                current.close();
            } catch (Exception exception) {
                System.err.println("[NSM] Failed to stop memory monitor");
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

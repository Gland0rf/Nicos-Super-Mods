package com.nico.client.lifecycle;

import com.nico.client.dungeon.DungeonScanner;
import com.nico.client.dungeon.DungeonState;
import com.nico.client.dungeon.DungeonStatsTracker;
import com.nico.client.dungeon.DungeonTeammateScanner;
import com.nico.client.secretTimer.SecretRoomTimerClient;
import com.nico.client.stacking.RoomStackingDetector;
import com.nico.client.utils.LocationUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/** Owns cleanup of NSM state that is scoped to the current world/server session. */
public final class WorldStateCleanup {
    private static final Logger LOGGER = LoggerFactory.getLogger("NSM/WorldStateCleanup");
    private static boolean registered;

    public record Result(List<String> cleanedSystems, List<String> failedSystems) {
        public Result {
            cleanedSystems = List.copyOf(cleanedSystems);
            failedSystems = List.copyOf(failedSystems);
        }

        public boolean successful() {
            return failedSystems.isEmpty();
        }
    }

    private WorldStateCleanup() { }

    public static synchronized void register() {
        if (registered) return;

        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> cleanupWorldState()
        );
        registered = true;
    }

    public static Result cleanupWorldState() {
        List<String> cleaned = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        run("location cache", LocationUtils::reset, cleaned, failed);
        run("dungeon state", DungeonState::reset, cleaned, failed);
        run("dungeon stats", DungeonStatsTracker::reset, cleaned, failed);
        run("dungeon scanner", DungeonScanner::clearTransientState, cleaned, failed);
        run("dungeon teammate cache", DungeonTeammateScanner::clearTransientState, cleaned, failed);
        run("secret timer", SecretRoomTimerClient::clearTransientState, cleaned, failed);
        run("room stacking", RoomStackingDetector::clearTransientState, cleaned, failed);

        return new Result(cleaned, failed);
    }

    private static void run(String name, Runnable cleanup, List<String> cleaned, List<String> failed) {
        try {
            cleanup.run();
            cleaned.add(name);
        } catch (Throwable throwable) {
            failed.add(name);
            LOGGER.warn("Failed to clean transient NSM state for {}", name, throwable);
        }
    }
}

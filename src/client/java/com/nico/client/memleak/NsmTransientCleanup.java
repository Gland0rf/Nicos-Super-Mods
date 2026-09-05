package com.nico.client.memleak;

import com.nico.client.dungeon.DungeonScanner;
import com.nico.client.dungeon.DungeonTeammateScanner;
import com.nico.client.secretTimer.SecretRoomTimerClient;
import com.nico.client.stacking.RoomStackingDetector;
import com.nico.client.stacking.SecretStackingDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class NsmTransientCleanup {
    private static final Logger LOGGER = LoggerFactory.getLogger("NSM/MemoryCleanup");

    public record Result(List<String> cleanedSystems, List<String> failedSystems) {
        public Result {
            cleanedSystems = List.copyOf(cleanedSystems);
            failedSystems = List.copyOf(failedSystems);
        }

        public boolean successful() {
            return failedSystems.isEmpty();
        }
    }

    private NsmTransientCleanup() { }

    public static Result cleanupWorldState() {
        List<String> cleaned = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        run("dungeon scanner", DungeonScanner::clearTransientState, cleaned, failed);
        run("dungeon teammate cache", DungeonTeammateScanner::clearTransientState, cleaned, failed);
        run("secret timer", SecretRoomTimerClient::clearTransientState, cleaned, failed);
        run("room stacking", RoomStackingDetector::clearTransientState, cleaned, failed);
        run("secret stacking", SecretStackingDetector::clearTransientState, cleaned, failed);

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

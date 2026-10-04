package com.nico.client.secretTimer;

import net.minecraft.core.BlockPos;

import java.util.*;
import java.util.function.BiConsumer;

/**
 + * Correlates local secret interactions with the room-secret counter reported by the server.
 + *
 + * <p>Several secret types are observed before the authoritative counter changes, while some
 + * packet paths can arrive in the opposite order. This class owns the short-lived evidence used
 + * to match those events without leaking the queue bookkeeping into the timer lifecycle.</p>
 + */
final class SecretPickupCorrelation {
    private static final long CONFIRM_WINDOW_MS = 5_000L;
    private static final long DUPLICATE_BLOCK_INTERACTION_WINDOW_MS = 250L;
    private static final double CHEST_DROP_IGNORE_RADIUS_SQ = 2.0D * 2.0D;

    private final Map<String, Deque<Long>> pendingSelfPickups = new HashMap<>();
    private final Map<String, Deque<Long>> pendingCounterIncrements = new HashMap<>();
    private final Map<String, Deque<Long>> pendingIgnoredSelfPickups = new HashMap<>();
    private final Map<String, Deque<PotentialSecret>> pendingPotentialSecrets = new HashMap<>();
    private final Map<String, Long> lastConfirmedBlockSecretAt = new HashMap<>();
    private final Map<String, List<BlockPos>> chestSecretPositions = new HashMap<>();
    private final Map<String, Deque<PendingChestSecret>> pendingChestSecrets = new HashMap<>();
    private final Map<String, Set<Long>> seenSecretPositions = new HashMap<>();

    void recordIgnoredSelfPickup(String roomName, long now) {
        Deque<Long> ignoredPickups = pendingIgnoredSelfPickups
                .computeIfAbsent(roomName, ignored -> new ArrayDeque<>());

        if (ignoredPickups.isEmpty()
                || now - ignoredPickups.peekLast() > DUPLICATE_BLOCK_INTERACTION_WINDOW_MS) {
            ignoredPickups.addLast(now);
        }
    }

    void recordSelfPickup(String roomName, long now) {
        pendingSelfPickups
                .computeIfAbsent(roomName, ignored -> new ArrayDeque<>())
                .addLast(now);
    }

    boolean markPositionSeen(String roomName, BlockPos pos) {
        return seenSecretPositions
                .computeIfAbsent(roomName, ignored -> new HashSet<>())
                .add(pos.asLong());
    }

    boolean hasSeenPosition(String roomName, BlockPos pos) {
        if (pos == null) return false;

        Set<Long> seenPositions = seenSecretPositions.get(roomName);
        return seenPositions != null && seenPositions.contains(pos.asLong());
    }

    boolean consumePendingSelfPickup(String roomName, long now) {
        return consumeTimestamp(pendingSelfPickups, roomName, now);
    }

    boolean consumePendingIgnoredSelfPickup(String roomName, long now) {
        return consumeTimestamp(pendingIgnoredSelfPickups, roomName, now);
    }

    boolean consumePendingCounterIncrement(
            String roomName,
            long now,
            BiConsumer<String, Long> expiredIncrementHandler
    ) {
        Deque<Long> queue = pendingCounterIncrements.get(roomName);
        if (queue == null) return false;

        while (!queue.isEmpty() && isExpired(queue.peekFirst(), now)) {
            expiredIncrementHandler.accept(roomName, queue.removeFirst());
        }

        boolean matched = !queue.isEmpty();
        if (matched) {
            queue.removeFirst();
            }

        if (queue.isEmpty()) {
            pendingCounterIncrements.remove(roomName);
        }

        return matched;
    }

    void addPendingCounterIncrement(String roomName, long now) {
        pendingCounterIncrements
                .computeIfAbsent(roomName, ignored -> new ArrayDeque<>())
                .addLast(now);
    }

    void expireCounterIncrements(long now, BiConsumer<String, Long> expiredIncrementHandler) {
        Iterator<Map.Entry<String, Deque<Long>>> iterator = pendingCounterIncrements.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<String, Deque<Long>> entry = iterator.next();
            Deque<Long> queue = entry.getValue();

            while (!queue.isEmpty() && isExpired(queue.peekFirst(), now)) {
                expiredIncrementHandler.accept(entry.getKey(), queue.removeFirst());
            }

            if (queue.isEmpty()) {
                iterator.remove();
            }
        }
    }

    void queuePotentialSecret(String roomName, BlockPos pos, PotentialSecretKind kind, long now) {
        Deque<PotentialSecret> queue = pendingPotentialSecrets
                .computeIfAbsent(roomName, ignored -> new ArrayDeque<>());

        if (pos != null) {
            queue.removeIf(pending -> pos.equals(pending.position));
        }

        queue.addLast(new PotentialSecret(
                pos == null ? null : pos.immutable(),
                now,
                kind
        ));
    }

    PotentialSecret consumePendingPotentialSecret(String roomName, long now) {
        Deque<PotentialSecret> queue = pendingPotentialSecrets.get(roomName);
        if (queue == null || queue.isEmpty()) return null;

        while (!queue.isEmpty() && isExpired(queue.peekFirst().observedAtMs, now)) {
            queue.removeFirst();
        }

        PotentialSecret matched = queue.pollLast();
        if (queue.isEmpty()) {
            pendingPotentialSecrets.remove(roomName);
        }

        return matched;
    }

    void rememberConfirmedPotentialSecret(String roomName, PotentialSecret secret, long now) {
        if (secret != null) {
            rememberConfirmedPotentialSecret(roomName, secret.kind, now);
        }
    }

    void rememberConfirmedPotentialSecret(String roomName, PotentialSecretKind kind, long now) {
        if (kind == PotentialSecretKind.BLOCK) {
            lastConfirmedBlockSecretAt.put(roomName, now);
        }
    }

    boolean hasRecentPendingBlockSecret(String roomName, long now) {
        Deque<PotentialSecret> queue = pendingPotentialSecrets.get(roomName);
        if (queue == null || queue.isEmpty()) return false;

        while (!queue.isEmpty() && isExpired(queue.peekFirst().observedAtMs, now)) {
            queue.removeFirst();
        }

        if (queue.isEmpty()) {
            pendingPotentialSecrets.remove(roomName);
            return false;
        }

        for (PotentialSecret secret : queue) {
            if (secret.kind == PotentialSecretKind.BLOCK) return true;
        }
        return false;
    }

    boolean wasBlockSecretRecentlyConfirmed(String roomName, long now) {
        Long confirmedAt = lastConfirmedBlockSecretAt.get(roomName);
        if (confirmedAt == null) return false;

        if (isExpired(confirmedAt, now)) {
            lastConfirmedBlockSecretAt.remove(roomName);
            return false;
        }

        return true;
    }

    void queueChestSecret(String roomName, BlockPos chestPos, long now) {
        Deque<PendingChestSecret> queue = pendingChestSecrets
                .computeIfAbsent(roomName, ignored -> new ArrayDeque<>());

        BlockPos immutablePos = chestPos.immutable();
        queue.removeIf(pending -> pending.position.equals(immutablePos));
        queue.addLast(new PendingChestSecret(immutablePos, now));
    }

    BlockPos removeLastPendingChest(String roomName) {
        Deque<PendingChestSecret> queue = pendingChestSecrets.get(roomName);
        if (queue == null || queue.isEmpty()) return null;

        BlockPos removed = queue.removeLast().position;
        if (queue.isEmpty()) {
            pendingChestSecrets.remove(roomName);
        }
        return removed;
    }

    BlockPos consumePendingChestSecret(String roomName, long now) {
        Deque<PendingChestSecret> queue = pendingChestSecrets.get(roomName);
        if (queue == null || queue.isEmpty()) return null;

        while (!queue.isEmpty() && isExpired(queue.peekFirst().clickedAtMs, now)) {
            queue.removeFirst();
        }

        PendingChestSecret matched = queue.pollLast();
        if (queue.isEmpty()) {
            pendingChestSecrets.remove(roomName);
        }

        return matched == null ? null : matched.position;
    }

    void rememberChestSecretPosition(String roomName, BlockPos chestPos) {
        List<BlockPos> positions = chestSecretPositions
                .computeIfAbsent(roomName, ignored -> new ArrayList<>());
        BlockPos immutablePos = chestPos.immutable();

        if (!positions.contains(immutablePos)) {
            positions.add(immutablePos);
        }

        System.out.println("[NSM] Remembered chest secret at " + chestPos + " in room " + roomName);
    }

    boolean isNearRememberedChestSecret(String roomName, BlockPos itemPos) {
        if (itemPos == null) return false;

        List<BlockPos> chestPositions = chestSecretPositions.get(roomName);
        if (chestPositions == null || chestPositions.isEmpty()) return false;

        for (BlockPos chestPos : chestPositions) {
            if (chestPos.distSqr(itemPos) <= CHEST_DROP_IGNORE_RADIUS_SQ) {
                System.out.println("[NSM] Item pickup at " + itemPos
                        + " ignored because it is near chest secret at " + chestPos
                         + " in room " + roomName);
                return true;
            }
        }

        return false;
    }

    void clearRoom(String roomName) {
        pendingSelfPickups.remove(roomName);
        pendingCounterIncrements.remove(roomName);
        pendingIgnoredSelfPickups.remove(roomName);
        pendingPotentialSecrets.remove(roomName);
        lastConfirmedBlockSecretAt.remove(roomName);
        chestSecretPositions.remove(roomName);
        pendingChestSecrets.remove(roomName);
        seenSecretPositions.remove(roomName);
    }

    void clear() {
        pendingSelfPickups.clear();
        pendingCounterIncrements.clear();
        pendingIgnoredSelfPickups.clear();
        pendingPotentialSecrets.clear();
        lastConfirmedBlockSecretAt.clear();
        chestSecretPositions.clear();
        pendingChestSecrets.clear();
        seenSecretPositions.clear();
    }

    private boolean consumeTimestamp(Map<String, Deque<Long>> queues, String roomName, long now) {
        Deque<Long> queue = queues.get(roomName);
        if (queue == null) return false;

        while (!queue.isEmpty() && isExpired(queue.peekFirst(), now)) {
            queue.removeFirst();
        }

        boolean matched = !queue.isEmpty();
        if (matched) {
            queue.removeFirst();
        }

        if (queue.isEmpty()) {
            queues.remove(roomName);
        }

        return matched;
    }

    private boolean isExpired(long observedAtMs, long now) {
        return now - observedAtMs > CONFIRM_WINDOW_MS;
    }

    enum PotentialSecretKind {
        ITEM,
        COMBAT,
        BLOCK
    }

    static final class PotentialSecret {
        private final BlockPos position;
        private final long observedAtMs;
        private final PotentialSecretKind kind;

        private PotentialSecret(BlockPos position, long observedAtMs, PotentialSecretKind kind) {
            this.position = position;
            this.observedAtMs = observedAtMs;
            this.kind = kind;
        }

        BlockPos position() {
            return position;
        }
    }

    private static final class PendingChestSecret {
        private final BlockPos position;
        private final long clickedAtMs;

        private PendingChestSecret(BlockPos position, long clickedAtMs) {
            this.position = position;
            this.clickedAtMs = clickedAtMs;
        }
    }
}
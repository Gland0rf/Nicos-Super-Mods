package com.nico.client.lag;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;

public class LagMonitorService {
    private static final LagMonitorService INSTANCE = new LagMonitorService();

    private final TpsEstimator tpsEstimator = new TpsEstimator();
    private final PingTracker pingTracker = new PingTracker();
    private final LagTitleNotifier titleNotifier = new LagTitleNotifier();

    private volatile long lastInboundPacketNanos;
    private volatile LagSnapshot snapshot = LagSnapshot.inactive();
    private volatile LagSessionSummary lastSummary;

    private LagMonitorConfig config = new LagMonitorConfig();
    private LagSessionStats dungeonStats;
    private boolean dungeonRunActive;
    private long joinedNanos;
    private long lastTickNanos;
    private int clientTicks;
    private int pendingSummaryTicks = -1;

    private LagMonitorService() {
    }

    public static LagMonitorService getInstance() {
        return INSTANCE;
    }

    public synchronized void configure(LagMonitorConfig config) {
        this.config = config == null ? new LagMonitorConfig() : config;
    }

    public LagMonitorConfig config() {
        return config;
    }

    public LagSnapshot snapshot() {
        return snapshot;
    }

    public LagSessionSummary lastSummary() {
        return lastSummary;
    }

    public synchronized boolean isDungeonRunActive() {
        return dungeonRunActive;
    }

    public void onInboundPacket(Packet<?> packet) {
        long now = System.nanoTime();
        lastInboundPacketNanos = now;

        if (packet instanceof ClientboundSetTimePacket timePacket) {
            tpsEstimator.onTimePacket(timePacket.gameTime(), now);
        }
    }

    public synchronized void onJoin(Minecraft client) {
        long now = System.nanoTime();
        tpsEstimator.reset();
        pingTracker.reset();
        titleNotifier.reset();
        lastInboundPacketNanos = now;
        joinedNanos = now;
        lastTickNanos = now;
        clientTicks = 0;
        dungeonStats = null;
        dungeonRunActive = false;
        snapshot = LagSnapshot.inactive();

        if (lastSummary != null && config.showPreviousSummaryAfterJoin) {
            pendingSummaryTicks = 40;
        } else {
            pendingSummaryTicks = -1;
        }
    }

    public synchronized void onDisconnect() {
        // A disconnect is not a completed dungeon run, so discard partial data.
        dungeonStats = null;
        dungeonRunActive = false;
        snapshot = LagSnapshot.inactive();
        pendingSummaryTicks = -1;
    }

    /** Call once when the dungeon run actually starts. */
    public synchronized void onDungeonRunStart() {
        if (!config.enabled) {
            return;
        }

        long now = System.nanoTime();
        dungeonStats = new LagSessionStats(now);
        dungeonRunActive = true;
        pingTracker.reset();
    }

    /**
     * Call once when the dungeon completion is confirmed. Duplicate end calls
     * are ignored, which is useful when multiple completion packets arrive.
     */
    public synchronized void onDungeonRunEnd(Minecraft client) {
        if (!dungeonRunActive || dungeonStats == null) {
            return;
        }

        lastSummary = dungeonStats.finish(System.nanoTime());
        dungeonStats = null;
        dungeonRunActive = false;

        if (config.showDungeonSummary) {
            sendSummary(client, lastSummary);
        }
    }

    /** Call when leaving/aborting a run without completing it. */
    public synchronized void onDungeonRunAbort() {
        dungeonStats = null;
        dungeonRunActive = false;
    }

    public synchronized void tick(Minecraft client) {
        long now = System.nanoTime();
        double deltaSeconds = Math.max(
                0.0D,
                Math.min(0.25D, (now - lastTickNanos) / 1_000_000_000.0D)
        );
        lastTickNanos = now;
        clientTicks++;

        boolean connected = client.player != null
                && client.level != null
                && client.getConnection() != null;
        boolean allowedServer = !config.onlyOnHypixel || HypixelServerDetector.isHypixel(client);
        boolean active = config.enabled && connected && allowedServer;

        if (!active) {
            snapshot = LagSnapshot.inactive();
            return;
        }

        if (clientTicks % config.pingSampleIntervalTicks == 0) {
            int ping = readPing(client);
            pingTracker.addSample(ping);
            if (dungeonStats != null) {
                dungeonStats.recordPingSample(ping, pingTracker.jitter());
            }
        }

        double tps = tpsEstimator.currentTps(now, config.tpsSampleStaleSeconds);
        int ping = pingTracker.latest();
        double jitter = pingTracker.jitter();
        long packetGapMillis = Math.max(0L, (now - lastInboundPacketNanos) / 1_000_000L);
        int fps = client.getFps();
        boolean warmingUp = now - joinedNanos < config.warmupSeconds * 1_000_000_000L;

        LagDiagnosis diagnosis = diagnose(tps, ping, jitter, packetGapMillis, fps, warmingUp);
        double serverDelay = dungeonStats == null
                ? 0.0D
                : dungeonStats.estimatedServerDelaySeconds();
        double stallSeconds = dungeonStats == null
                ? 0.0D
                : dungeonStats.networkStallSeconds();

        LagSnapshot next = new LagSnapshot(
                true,
                warmingUp,
                tps,
                ping,
                jitter,
                packetGapMillis,
                fps,
                serverDelay,
                stallSeconds,
                diagnosis
        );

        if (dungeonStats != null) {
            dungeonStats.accept(next, deltaSeconds, config);
            next = new LagSnapshot(
                    next.active(),
                    next.warmingUp(),
                    next.estimatedTps(),
                    next.pingMillis(),
                    next.jitterMillis(),
                    next.packetGapMillis(),
                    next.fps(),
                    dungeonStats.estimatedServerDelaySeconds(),
                    dungeonStats.networkStallSeconds(),
                    next.diagnosis()
            );
        }

        snapshot = next;
        titleNotifier.tick(client, next, config, now);
        tickPendingSummary(client);
    }

    private LagDiagnosis diagnose(
            double tps,
            int ping,
            double jitter,
            long packetGapMillis,
            int fps,
            boolean warmingUp
    ) {
        if (warmingUp) {
            return LagDiagnosis.WARMING_UP;
        }
        if (packetGapMillis >= config.stallWarningMillis) {
            return LagDiagnosis.STALLED;
        }

        boolean serverLag = Double.isFinite(tps) && tps < config.serverWarningTps;
        boolean networkLag = ping >= config.highPingWarningMillis
                || (Double.isFinite(jitter) && jitter >= config.highJitterWarningMillis);

        if (serverLag && networkLag) {
            return LagDiagnosis.MIXED;
        }
        if (serverLag) {
            return LagDiagnosis.SERVER_LAG;
        }
        if (networkLag) {
            return LagDiagnosis.NETWORK_LAG;
        }
        if (fps > 0 && fps < config.lowFpsThreshold) {
            return LagDiagnosis.CLIENT_LAG;
        }
        if (!Double.isFinite(tps) && ping < 0) {
            return LagDiagnosis.UNKNOWN;
        }
        return LagDiagnosis.GOOD;
    }

    private static int readPing(Minecraft client) {
        PlayerInfo info = client.getConnection().getPlayerInfo(client.player.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    private static void sendSummary(Minecraft client, LagSessionSummary summary) {
        if (client == null || client.player == null || summary == null) {
            return;
        }
        for (var line : summary.compactChatLines()) {
            client.player.sendSystemMessage(line);
        }
    }

    private void tickPendingSummary(Minecraft client) {
        if (pendingSummaryTicks < 0 || lastSummary == null) {
            return;
        }
        if (pendingSummaryTicks-- > 0) {
            return;
        }
        sendSummary(client, lastSummary);
        pendingSummaryTicks = -1;
    }
}
package com.nico.client.lag;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;

public class LagMonitorService {
    private static final LagMonitorService INSTANCE = new LagMonitorService();

    private final TpsEstimator tpsEstimator = new TpsEstimator();
    private final PingTracker pingTracker = new PingTracker();
    private final LagTitleNotifier titleNotifier = new LagTitleNotifier();
    private final ConnectionPingTracker connectionPingTracker = new ConnectionPingTracker();

    private volatile long lastInboundPacketNanos;
    private volatile LagSnapshot snapshot = LagSnapshot.inactive();
    private volatile LagSessionSummary lastSummary;

    private LagMonitorConfig config = new LagMonitorConfig();
    private LagSessionStats dungeonStats;
    private boolean dungeonRunActive;
    private long joinedNanos;
    private long lastTickNanos;
    private int clientTicks;
    private long lastDebugHeartbeatNanos;

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

        if (packet instanceof ClientboundPingPacket pingPacket
                && pingPacket.getId() != 0) {
            tpsEstimator.onServerTick(now);
        }

        if (packet instanceof ClientboundPongResponsePacket pongPacket) {
            int measuredPing = connectionPingTracker.accept(pongPacket);
            if (measuredPing >= 0) {
                onConnectionPingSample(measuredPing);
            }
        }
    }

    public synchronized void onJoin(Minecraft client) {
        long now = System.nanoTime();
        tpsEstimator.reset();
        pingTracker.reset();
        connectionPingTracker.reset();
        titleNotifier.reset();
        lastInboundPacketNanos = now;
        joinedNanos = now;
        lastTickNanos = now;
        clientTicks = 0;
        dungeonStats = null;
        dungeonRunActive = false;
        snapshot = LagSnapshot.inactive();
    }

    public synchronized void onDisconnect() {
        tpsEstimator.reset();
        pingTracker.reset();
        connectionPingTracker.reset();
        dungeonStats = null;
        dungeonRunActive = false;
        snapshot = LagSnapshot.inactive();
    }

    public synchronized void onDungeonRunStart() {
        Minecraft client = Minecraft.getInstance();
        long nowNanos = System.nanoTime();

        if (!config.enabled || dungeonRunActive) {
            return;
        }

        dungeonStats = new LagSessionStats(nowNanos);
        dungeonRunActive = true;
        lastDebugHeartbeatNanos = 0L;

        int cachedPing = connectionPingTracker.latest();
        if (cachedPing >= 0) {
            pingTracker.addSample(cachedPing);
            dungeonStats.recordPingSample(cachedPing, pingTracker.jitter());
        }

        connectionPingTracker.requestNow(
                client,
                config.tcpPingTimeoutMillis
        );
    }

    private synchronized void onConnectionPingSample(int pingMillis) {
        if (pingMillis < 0) {
            return;
        }

        pingTracker.addSample(pingMillis);
        if (dungeonRunActive && dungeonStats != null) {
            dungeonStats.recordPingSample(pingMillis, pingTracker.jitter());
        }
    }

    public synchronized void onDungeonRunEnd(Minecraft client) {
        if (!dungeonRunActive) {
            return;
        }

        if (!dungeonRunActive || dungeonStats == null) {
            return;
        }

        if (dungeonStats.pingSampleCount() == 0 && connectionPingTracker.latest() >= 0) {
            int ping = connectionPingTracker.latest();
            pingTracker.addSample(ping);
            dungeonStats.recordPingSample(ping, pingTracker.jitter());
        }

        LagSessionSummary completedSummary = dungeonStats.finish(System.nanoTime(), config);

        lastSummary = completedSummary;
        dungeonStats = null;
        dungeonRunActive = false;

        if (config.showEndReport) {
            sendSummary(client, completedSummary);
        }
        if (config.copyTpsLossToClipboard) {
            copyTpsLossToClipboard(client, completedSummary);
        }
    }

    public synchronized void onDungeonRunAbort() {
        if (!dungeonRunActive) {
            return;
        }

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
        boolean allowedServer = HypixelServerDetector.isHypixel(client);
        boolean correctContext =
                !config.onlyShowInDungeons || dungeonRunActive;

        boolean active = config.enabled
                && connected
                && correctContext
                && (dungeonRunActive || allowedServer);

        if (!active) {
            snapshot = LagSnapshot.inactive();
            return;
        }

        connectionPingTracker.tick(
                client,
                config.tcpPingSampleIntervalSeconds,
                config.tcpPingTimeoutMillis
        );

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

        if (dungeonRunActive && dungeonStats != null) {
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

        if (config.debugLogging && dungeonRunActive
                && now - lastDebugHeartbeatNanos >= 5_000_000_000L) {
            lastDebugHeartbeatNanos = now;
        }

        titleNotifier.tick(client, next, config, now);
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

    private static void copyTpsLossToClipboard(
            Minecraft client,
            LagSessionSummary summary
    ) {
        if (client == null || client.player == null || summary == null) {
            return;
        }

        try {
            client.keyboardHandler.setClipboard("[NSM] Total loss by TPS: " + summary.tpsLossClipboardText());
            client.player.displayClientMessage(summary.clipboardConfirmationLine(), false);
        } catch (RuntimeException exception) {
            System.err.println("[NSM Lag] Could not copy TPS loss: " + exception.getMessage());
        }
    }

    private static void sendSummary(Minecraft client, LagSessionSummary summary) {
        if (client == null || client.player == null || summary == null) {
            System.out.println("[NSM Lag][Debug] Summary send skipped because client/player/summary is null");
            return;
        }

        var lines = summary.compactChatLines();
        System.out.println("[NSM Lag][Debug] Sending " + lines.size() + " summary chat lines");
        for (var line : lines) {
            client.player.displayClientMessage(line, false);
        }
    }

    private void debug(String message) {
        if (config != null && config.debugLogging) {
            System.out.println("[NSM Lag][Debug] " + message);
        }
    }
}
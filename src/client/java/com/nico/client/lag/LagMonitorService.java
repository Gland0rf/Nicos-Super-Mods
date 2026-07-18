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
    private int pendingSummaryTicks = -1;
    private long lastDebugHeartbeatNanos;

    private LagMonitorService() {
    }

    public static LagMonitorService getInstance() {
        return INSTANCE;
    }

    public synchronized void configure(LagMonitorConfig config) {
        this.config = config == null ? new LagMonitorConfig() : config;
        debug("Configured: enabled=" + this.config.enabled
                + ", onlyOnHypixel=" + this.config.onlyOnHypixel
                + ", showDungeonSummary=" + this.config.showDungeonSummary
                + ", debugLogging=" + this.config.debugLogging);
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

        // Same server-tick signal used by Odin: Hypixel sends a non-zero
        // ClientboundPingPacket once per server tick. This is independent of
        // dungeon timers, scoreboard text, and ClientboundSetTimePacket.
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

        pendingSummaryTicks = lastSummary != null && config.showPreviousSummaryAfterJoin
                ? 40
                : -1;
    }

    public synchronized void onDisconnect() {
        tpsEstimator.reset();
        pingTracker.reset();
        connectionPingTracker.reset();
        dungeonStats = null;
        dungeonRunActive = false;
        snapshot = LagSnapshot.inactive();
        pendingSummaryTicks = -1;
        debug("Disconnected; cleared active dungeon tracking");
    }

    public synchronized void onDungeonRunStart() {
        Minecraft client = Minecraft.getInstance();
        long nowNanos = System.nanoTime();

        debug("Chat start requested: alreadyActive=" + dungeonRunActive
                + ", statsPresent=" + (dungeonStats != null));

        if (!config.enabled || dungeonRunActive) {
            return;
        }

        dungeonStats = new LagSessionStats(nowNanos);
        dungeonRunActive = true;
        pingTracker.reset();
        tpsEstimator.reset();
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

        System.out.println("[NSM Lag] Dungeon lag tracking started from chat"
                + " (cachedPlayPing=" + (cachedPing < 0 ? "N/A" : cachedPing + "ms") + ")");
    }

    private synchronized void onConnectionPingSample(int pingMillis) {
        if (pingMillis < 0) {
            return;
        }

        pingTracker.addSample(pingMillis);
        if (dungeonRunActive && dungeonStats != null) {
            dungeonStats.recordPingSample(pingMillis, pingTracker.jitter());
        }

        debug("Active-connection ping sample=" + pingMillis + "ms, active=" + dungeonRunActive);
    }

    public synchronized void onDungeonRunEnd(Minecraft client) {
        debug("End requested: active=" + dungeonRunActive
                + ", statsPresent=" + (dungeonStats != null));

        if (!dungeonRunActive || dungeonStats == null) {
            System.out.println("[NSM Lag] Dungeon end ignored: no active tracked run");
            return;
        }

        if (dungeonStats.pingSampleCount() == 0 && connectionPingTracker.latest() >= 0) {
            int ping = connectionPingTracker.latest();
            pingTracker.addSample(ping);
            dungeonStats.recordPingSample(ping, pingTracker.jitter());
        }

        LagSessionSummary completedSummary = dungeonStats.finish(System.nanoTime(), config);

        debug("Final sample counts: ping=" + dungeonStats.pingSampleCount()
                + ", serverTickPackets=" + tpsEstimator.totalServerTicks()
                + ", packetTpsSeconds=" + dungeonStats.packetTpsSampleSeconds());

        lastSummary = completedSummary;
        dungeonStats = null;
        dungeonRunActive = false;
        tpsEstimator.reset();

        System.out.println("[NSM Lag] Dungeon lag tracking finished");
        debug("Summary values: duration=" + completedSummary.sessionSeconds()
                + "s, avgTps=" + completedSummary.averageTps()
                + ", avgPing=" + completedSummary.averagePingMillis()
                + "ms, tpsLoss=" + completedSummary.estimatedServerDelaySeconds()
                + "s, pingLoss=" + completedSummary.estimatedPingDelaySeconds()
                + "s, stalls=" + completedSummary.networkStallSeconds() + "s");

        if (config.showDungeonSummary) {
            sendSummary(client, completedSummary);
        }
        if (config.copyTpsLossToClipboard) {
            copyTpsLossToClipboard(client, completedSummary);
        }
    }

    public synchronized void onDungeonRunAbort() {
        debug("Abort requested: active=" + dungeonRunActive
                + ", statsPresent=" + (dungeonStats != null));
        dungeonStats = null;
        dungeonRunActive = false;
        tpsEstimator.reset();
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
        // Once a chat message has positively identified a dungeon run, do not
        // disable sampling merely because getCurrentServer() exposes a proxy or
        // non-Hypixel hostname. That gate previously allowed ping callbacks but
        // prevented tick-based TPS and loss accumulation.
        boolean active = config.enabled
                && connected
                && (dungeonRunActive || allowedServer);

        if (!active) {
            snapshot = LagSnapshot.inactive();
            return;
        }

        if (dungeonRunActive) {
            connectionPingTracker.tick(
                    client,
                    config.tcpPingSampleIntervalSeconds,
                    config.tcpPingTimeoutMillis
            );
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
            debug("Heartbeat: serverTickTps="
                    + (Double.isFinite(tps)
                    ? String.format(java.util.Locale.US, "%.2f", tps)
                    : "N/A")
                    + ", playPing=" + ping
                    + ", pingSamples=" + (dungeonStats == null ? 0 : dungeonStats.pingSampleCount())
                    + ", serverTickPackets=" + tpsEstimator.totalServerTicks()
                    + ", tickWindowSamples=" + tpsEstimator.windowSampleCount()
                    + ", packetTpsSeconds="
                    + (dungeonStats == null ? 0.0D : dungeonStats.packetTpsSampleSeconds())
                    + ", packetGap=" + packetGapMillis + "ms");
        }

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
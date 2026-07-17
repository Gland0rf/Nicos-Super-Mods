package com.nico.client.lag;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;

final class LagTitleNotifier {
    private long lowTpsSince;
    private long highPingSince;
    private long stalledSince;
    private long lastAlertNanos;

    void reset() {
        lowTpsSince = 0L;
        highPingSince = 0L;
        stalledSince = 0L;
        lastAlertNanos = 0L;
    }

    void tick(Minecraft client, LagSnapshot snapshot, LagMonitorConfig config, long nowNanos) {
        if (!config.showTitles || !snapshot.active() || snapshot.warmingUp()) {
            clearConditionTimers();
            return;
        }

        lowTpsSince = updateSince(
                snapshot.hasTpsEstimate() && snapshot.estimatedTps() <= config.serverCriticalTps,
                lowTpsSince,
                nowNanos
        );
        highPingSince = updateSince(
                snapshot.hasPing() && snapshot.pingMillis() >= config.highPingCriticalMillis,
                highPingSince,
                nowNanos
        );
        stalledSince = updateSince(
                snapshot.packetGapMillis() >= config.stallWarningMillis,
                stalledSince,
                nowNanos
        );

        if (nowNanos - lastAlertNanos < config.titleCooldownMillis * 1_000_000L) {
            return;
        }

        if (elapsedMillis(stalledSince, nowNanos) >= config.stallTitleDelayMillis) {
            show(
                    client,
                    "CONNECTION STALLED",
                    "No server response for " + String.format(Locale.US, "%.1fs", snapshot.packetGapMillis() / 1000.0D),
                    ChatFormatting.RED
            );
            lastAlertNanos = nowNanos;
            return;
        }

        if (elapsedMillis(lowTpsSince, nowNanos) >= config.tpsTitleDelayMillis) {
            show(
                    client,
                    "SERVER LAG",
                    "Estimated TPS: " + String.format(Locale.US, "%.1f", snapshot.estimatedTps())
                            + " • " + String.format(Locale.US, "%.1fs delay", snapshot.estimatedServerDelaySeconds()),
                    ChatFormatting.GOLD
            );
            lastAlertNanos = nowNanos;
            return;
        }

        if (elapsedMillis(highPingSince, nowNanos) >= config.pingTitleDelayMillis) {
            show(
                    client,
                    "HIGH PING",
                    snapshot.pingMillis() + " ms • "
                            + (Double.isFinite(snapshot.jitterMillis())
                            ? Math.round(snapshot.jitterMillis()) + " ms jitter"
                            : "jitter unavailable"),
                    ChatFormatting.RED
            );
            lastAlertNanos = nowNanos;
        }
    }

    private static void show(Minecraft client, String title, String subtitle, ChatFormatting color) {
        client.gui.setTimes(5, 40, 10);
        client.gui.setSubtitle(Component.literal(subtitle).withStyle(ChatFormatting.GRAY));
        client.gui.setTitle(Component.literal(title).withStyle(color, ChatFormatting.BOLD));
    }

    private static long updateSince(boolean condition, long since, long nowNanos) {
        if (!condition) {
            return 0L;
        }
        return since == 0L ? nowNanos : since;
    }

    private static long elapsedMillis(long since, long nowNanos) {
        return since == 0L ? -1L : (nowNanos - since) / 1_000_000L;
    }

    private void clearConditionTimers() {
        lowTpsSince = 0L;
        highPingSince = 0L;
        stalledSince = 0L;
    }
}

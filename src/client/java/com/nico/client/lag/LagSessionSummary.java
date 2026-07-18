package com.nico.client.lag;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record LagSessionSummary(
        double sessionSeconds,
        double estimatedServerDelaySeconds,
        double estimatedPingDelaySeconds,
        double networkStallSeconds,
        double averageTps,
        double lowestTps,
        double secondsBelow15Tps,
        double secondsBelow10Tps,
        double averagePingMillis,
        int p95PingMillis,
        int highestPingMillis,
        double averageJitterMillis,
        int tpsDropCount,
        int pingSpikeCount,
        int stallCount,
        double longestStallSeconds
) {
    public double estimatedNetworkDelaySeconds() {
        return estimatedPingDelaySeconds + networkStallSeconds;
    }

    public double estimatedTotalLostSeconds() {
        return estimatedServerDelaySeconds + estimatedNetworkDelaySeconds();
    }

    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("Run time: " + formatDuration(sessionSeconds));
        lines.add("Estimated total time lost: " + formatSeconds(estimatedTotalLostSeconds()));
        lines.add("TPS time lost: " + formatSeconds(estimatedServerDelaySeconds));
        lines.add("Ping/network time lost: " + formatSeconds(estimatedNetworkDelaySeconds()));
        lines.add("  Ping estimate: " + formatSeconds(estimatedPingDelaySeconds));
        lines.add("  Connection stalls: " + formatSeconds(networkStallSeconds));
        lines.add("");
        lines.add("Average estimated TPS: " + formatDouble(averageTps));
        lines.add("Lowest estimated TPS: " + formatDouble(lowestTps));
        lines.add("Time below 15 TPS: " + formatSeconds(secondsBelow15Tps));
        lines.add("Time below 10 TPS: " + formatSeconds(secondsBelow10Tps));
        lines.add("");
        lines.add("Average ping: " + formatPing(averagePingMillis));
        lines.add("95th percentile ping: " + (p95PingMillis < 0 ? "N/A" : p95PingMillis + " ms"));
        lines.add("Highest ping: " + (highestPingMillis < 0 ? "N/A" : highestPingMillis + " ms"));
        lines.add("Average jitter: " + formatPing(averageJitterMillis));
        lines.add("");
        lines.add("TPS drops: " + tpsDropCount);
        lines.add("Ping spikes: " + pingSpikeCount);
        lines.add("Connection stalls: " + stallCount);
        lines.add("Longest stall: " + formatSeconds(longestStallSeconds));
        return List.copyOf(lines);
    }

    public List<Component> compactChatLines() {
        Component prefix = Component.literal("[NSM] ")
                .withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD);

        List<Component> lines = new ArrayList<>();
        lines.add(prefix.copy().append(
                Component.literal("Dungeon Lag Report")
                        .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
        ));

        lines.add(prefix.copy()
                .append(Component.literal("Server  ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatDouble(averageTps) + " TPS")
                        .withStyle(tpsColor(averageTps), ChatFormatting.BOLD))
                .append(Component.literal("  •  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(formatSeconds(estimatedServerDelaySeconds) + " lost")
                        .withStyle(lossColor(estimatedServerDelaySeconds))));

        lines.add(prefix.copy()
                .append(Component.literal("Network ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatPing(averagePingMillis))
                        .withStyle(pingColor(averagePingMillis), ChatFormatting.BOLD))
                .append(Component.literal("  •  ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(formatSeconds(estimatedNetworkDelaySeconds()) + " lost")
                        .withStyle(lossColor(estimatedNetworkDelaySeconds()))));

        lines.add(prefix.copy()
                .append(Component.literal("Total estimated loss: ")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(formatSeconds(estimatedTotalLostSeconds()))
                        .withStyle(lossColor(estimatedTotalLostSeconds()), ChatFormatting.BOLD)));

        return List.copyOf(lines);
    }

    public Component clipboardConfirmationLine() {
        return Component.literal("[NSM] ")
                .withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD)
                .append(Component.literal("Copied TPS loss to clipboard: ")
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(formatSeconds(estimatedServerDelaySeconds))
                        .withStyle(ChatFormatting.AQUA));
    }

    public String tpsLossClipboardText() {
        return formatSeconds(estimatedServerDelaySeconds);
    }

    private static ChatFormatting tpsColor(double tps) {
        if (!Double.isFinite(tps)) return ChatFormatting.GRAY;
        if (tps >= 19.0D) return ChatFormatting.GREEN;
        if (tps >= 15.0D) return ChatFormatting.YELLOW;
        return ChatFormatting.RED;
    }

    private static ChatFormatting pingColor(double pingMillis) {
        if (!Double.isFinite(pingMillis)) return ChatFormatting.GRAY;
        if (pingMillis < 100.0D) return ChatFormatting.GREEN;
        if (pingMillis < 200.0D) return ChatFormatting.YELLOW;
        return ChatFormatting.RED;
    }

    private static ChatFormatting lossColor(double seconds) {
        if (!Double.isFinite(seconds)) return ChatFormatting.GRAY;
        if (seconds < 1.0D) return ChatFormatting.GREEN;
        if (seconds < 5.0D) return ChatFormatting.YELLOW;
        return ChatFormatting.RED;
    }

    private static String formatDuration(double seconds) {
        long rounded = Math.max(0L, Math.round(seconds));
        long hours = rounded / 3600L;
        long minutes = (rounded % 3600L) / 60L;
        long remainingSeconds = rounded % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%dh %02dm %02ds", hours, minutes, remainingSeconds);
        }
        return String.format(Locale.US, "%dm %02ds", minutes, remainingSeconds);
    }

    private static String formatSeconds(double seconds) {
        return Double.isFinite(seconds)
                ? String.format(Locale.US, "%.1fs", Math.max(0.0D, seconds))
                : "N/A";
    }

    private static String formatDouble(double value) {
        return Double.isFinite(value)
                ? String.format(Locale.US, "%.1f", value)
                : "N/A";
    }

    private static String formatPing(double value) {
        return Double.isFinite(value) ? Math.round(value) + " ms" : "N/A";
    }
}
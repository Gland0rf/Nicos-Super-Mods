package com.nico.client.lag;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record LagSessionSummary(
        double sessionSeconds,
        double estimatedServerDelaySeconds,
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
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("Run time: " + formatDuration(sessionSeconds));
        lines.add("Estimated server-lag delay: " + formatSeconds(estimatedServerDelaySeconds));
        lines.add("Connection stall time: " + formatSeconds(networkStallSeconds));
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
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Lag summary").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
        lines.add(Component.literal(
                "Delay: " + formatSeconds(estimatedServerDelaySeconds)
                        + "  Stalls: " + formatSeconds(networkStallSeconds)
        ).withStyle(ChatFormatting.GRAY));
        lines.add(Component.literal(
                "TPS avg/min: " + formatDouble(averageTps) + "/" + formatDouble(lowestTps)
                        + "  Ping avg/p95: " + formatPing(averagePingMillis) + "/"
                        + (p95PingMillis < 0 ? "N/A" : p95PingMillis + " ms")
        ).withStyle(ChatFormatting.GRAY));
        return List.copyOf(lines);
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
        return Double.isFinite(seconds) ? String.format(Locale.US, "%.1fs", Math.max(0.0D, seconds)) : "N/A";
    }

    private static String formatDouble(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.1f", value) : "N/A";
    }

    private static String formatPing(double value) {
        return Double.isFinite(value) ? Math.round(value) + " ms" : "N/A";
    }
}

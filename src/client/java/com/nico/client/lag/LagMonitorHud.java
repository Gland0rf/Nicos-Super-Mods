package com.nico.client.lag;

import com.nico.client.Main;
import com.nico.client.hud.HudElement;
import com.nico.client.hud.HudLayoutManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class LagMonitorHud {
    private static final Identifier ID =
            Identifier.fromNamespaceAndPath("nsm", "lag_monitor");

    private LagMonitorHud() {
    }

    static void register() {
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                ID,
                LagMonitorHud::render
        );
    }

    private static void render(
            GuiGraphicsExtractor graphics,
            DeltaTracker deltaTracker
    ) {
        LagMonitorService service = LagMonitorService.getInstance();
        LagMonitorConfig config = service.config();
        LagSnapshot snapshot = service.snapshot();

        if (!config.showHud || !snapshot.active()) {
            return;
        }

        HudLayoutManager layoutManager = Main.HUD_LAYOUT;
        if (layoutManager == null) {
            return;
        }

        HudElement element = layoutManager.get(HudLayoutManager.LAG_MONITOR);

        if (element == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        List<String> lines = buildLines(snapshot, config);
        if (lines.isEmpty()) return;

        int contentWidth = 0;
        for (String line : lines) {
            contentWidth = Math.max(contentWidth, client.font.width(line));
        }

        int textX = config.showHudAccentBar ? 7 : 4;
        int measuredWidth = contentWidth + textX + 4;
        int measuredHeight = lines.size() * 10 + 8;

        element.setMeasuredSize(measuredWidth, measuredHeight);

        float scale = (float) element.getScale();

        graphics.pose().pushMatrix();
        graphics.pose().translate(element.getX(), element.getY());
        graphics.pose().scale(scale, scale);

        if (config.showHudBackground) {
            int alpha = (config.hudBackgroundOpacity * 255 + 50) / 100;
            graphics.fill(
                    0,
                    0,
                    measuredWidth,
                    measuredHeight,
                    (alpha << 24) | 0x00101218
            );
        }

        if (config.showHudAccentBar) {
            graphics.fill(
                    0,
                    0,
                    3,
                    measuredHeight,
                    snapshot.diagnosis().color()
            );
        }

        int textY = 4;

        for (int index = 0; index < lines.size(); index++) {
            boolean diagnosisLine = config.showHudDiagnosis && index == lines.size() - 1;
            int color = diagnosisLine
                    ? snapshot.diagnosis().color()
                    : 0xFFFFFFFF;

            graphics.text(
                    client.font,
                    lines.get(index),
                    textX,
                    textY,
                    color,
                    config.hudTextShadow
            );

            textY += 10;
        }

        graphics.pose().popMatrix();
    }

    private static List<String> buildLines(
            LagSnapshot snapshot,
            LagMonitorConfig config
    ) {
        List<String> lines = new ArrayList<>();

        if (config.showHudHeader) {
            lines.add("Lag Monitor");
        }

        if (config.showHudTps) {
            String tps = snapshot.hasTpsEstimate()
                    ? String.format(Locale.US, "%.1f", snapshot.estimatedTps())
                    : "--";
            lines.add("TPS     " + tps);
        }

        if (config.showHudPing) {
            String ping = snapshot.hasPing()
                    ? snapshot.pingMillis() + " ms"
                    : "--";
            lines.add("Ping    " + ping);
        }

        if (config.showHudJitter) {
            String jitter = Double.isFinite(snapshot.jitterMillis())
                    ? Math.round(snapshot.jitterMillis()) + " ms"
                    : "--";
            lines.add("Jitter  " + jitter);
        }

        if (config.showHudPacketGap && snapshot.packetGapMillis() >= 500L) {
            lines.add(
                    "Gap     " + String.format(
                            Locale.US,
                            "%.1fs",
                            snapshot.packetGapMillis() / 1000.0D
                    )
            );
        }

        if (config.showHudDiagnosis) {
            lines.add(snapshot.diagnosis().label());
        }
        return lines;
    }
}

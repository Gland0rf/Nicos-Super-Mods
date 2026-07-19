package com.nico.client.lag;

import com.nico.client.Main;
import com.nico.client.hud.HudElement;
import com.nico.client.hud.HudLayoutManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
            GuiGraphics graphics,
            DeltaTracker deltaTracker
    ) {
        LagMonitorService service =
                LagMonitorService.getInstance();

        LagMonitorConfig config = service.config();
        LagSnapshot snapshot = service.snapshot();

        if (!config.showHud || !snapshot.active() || (config.onlyShowInDungeons && !service.isDungeonRunActive())) {
            return;
        }

        HudLayoutManager layoutManager = Main.HUD_LAYOUT;
        if (layoutManager == null) {
            return;
        }

        HudElement element =
                layoutManager.get(HudLayoutManager.LAG_MONITOR);

        if (element == null) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        List<String> lines = buildLines(snapshot);

        int contentWidth = 0;
        for (String line : lines) {
            contentWidth = Math.max(
                    contentWidth,
                    client.font.width(line)
            );
        }

        int measuredWidth = contentWidth + 12;
        int measuredHeight = lines.size() * 10 + 8;

        element.setMeasuredSize(measuredWidth, measuredHeight);

        float scale = (float) element.getScale();

        graphics.pose().pushMatrix();
        graphics.pose().translate(
                element.getX(),
                element.getY()
        );
        graphics.pose().scale(scale, scale);

        graphics.fill(
                0,
                0,
                measuredWidth,
                measuredHeight,
                0xB0101218
        );

        graphics.fill(
                0,
                0,
                3,
                measuredHeight,
                snapshot.diagnosis().color()
        );

        int textY = 4;

        for (int index = 0; index < lines.size(); index++) {
            int color = index == lines.size() - 1
                    ? snapshot.diagnosis().color()
                    : 0xFFFFFFFF;

            graphics.drawString(
                    client.font,
                    lines.get(index),
                    7,
                    textY,
                    color,
                    true
            );

            textY += 10;
        }

        graphics.pose().popMatrix();
    }

    private static List<String> buildLines(
            LagSnapshot snapshot
    ) {
        List<String> lines = new ArrayList<>();

        String tps = snapshot.hasTpsEstimate()
                ? String.format(
                Locale.US,
                "%.1f",
                snapshot.estimatedTps()
        )
                : "--";

        String ping = snapshot.hasPing()
                ? snapshot.pingMillis() + " ms"
                : "--";

        String jitter = Double.isFinite(snapshot.jitterMillis())
                ? Math.round(snapshot.jitterMillis()) + " ms"
                : "--";

        lines.add("[NSM] Dungeon Lag");
        lines.add("TPS     " + tps);
        lines.add("Ping    " + ping);
        lines.add("Jitter  " + jitter);

        if (snapshot.packetGapMillis() >= 500L) {
            lines.add(
                    "Gap     " + String.format(
                            Locale.US,
                            "%.1fs",
                            snapshot.packetGapMillis() / 1000.0D
                    )
            );
        }

        lines.add(snapshot.diagnosis().label());
        return lines;
    }
}

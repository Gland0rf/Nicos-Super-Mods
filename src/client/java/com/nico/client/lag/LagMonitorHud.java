package com.nico.client.lag;

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
    private static final Identifier ID = Identifier.fromNamespaceAndPath("nsm", "lag_monitor");

    private LagMonitorHud() {
    }

    static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ID, LagMonitorHud::extract);
    }

    private static void extract(GuiGraphics graphics, DeltaTracker deltaTracker) {
        LagMonitorService service = LagMonitorService.getInstance();
        LagMonitorConfig config = service.config();
        LagSnapshot snapshot = service.snapshot();

        if (!config.showHud || !snapshot.active()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        List<String> lines = new ArrayList<>();
        lines.add("TPS  " + (snapshot.hasTpsEstimate()
                ? String.format(Locale.US, "%.1f", snapshot.estimatedTps())
                : "--"));
        lines.add("Ping " + (snapshot.hasPing() ? snapshot.pingMillis() + " ms" : "--"));
        lines.add("Jitter " + (Double.isFinite(snapshot.jitterMillis())
                ? Math.round(snapshot.jitterMillis()) + " ms"
                : "--"));
        if (snapshot.packetGapMillis() >= 500L) {
            lines.add("Gap " + String.format(Locale.US, "%.1fs", snapshot.packetGapMillis() / 1000.0D));
        }
        lines.add(snapshot.diagnosis().label());

        int width = 0;
        for (String line : lines) {
            width = Math.max(width, client.font.width(line));
        }
        width += 12;
        int height = lines.size() * 10 + 8;
        int x = client.getWindow().getGuiScaledWidth() - width - config.hudXOffset;
        int y = config.hudYOffset;

        graphics.fill(x, y, x + width, y + height, 0xB0101218);
        graphics.fill(x, y, x + 3, y + height, snapshot.diagnosis().color());

        int textY = y + 4;
        for (int index = 0; index < lines.size(); index++) {
            int color = index == lines.size() - 1 ? snapshot.diagnosis().color() : 0xFFFFFFFF;
            graphics.drawString(client.font, lines.get(index), x + 7, textY, color, true);
            textY += 10;
        }
    }
}

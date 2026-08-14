package com.nico.client.lag;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class LagSummaryScreen extends Screen {
    private final Screen parent;
    private final LagSessionSummary summary;

    public LagSummaryScreen(Screen parent, LagSessionSummary summary) {
        super(Component.literal("Lag Summary"));
        this.parent = parent;
        this.summary = summary;
    }

    @Override
    protected void init() {
        addRenderableWidget(
                Button.builder(Component.literal("Close"), button -> onClose())
                        .bounds(width / 2 - 40, height - 30, 80, 20)
                        .build()
        );
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.fill(0, 0, width, height, 0xE0101218);
        graphics.centeredText(font, Component.literal("Lag Summary"), width / 2, 18, 0xFF55FFFF);

        List<String> lines = summary == null ? List.of("No completed session yet.") : summary.lines();
        int panelWidth = Math.min(430, width - 30);
        int panelLeft = (width - panelWidth) / 2;
        int panelTop = 38;
        int panelBottom = Math.min(height - 40, panelTop + lines.size() * 12 + 16);

        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelBottom, 0xCC1A1D28);
        graphics.fill(panelLeft, panelTop, panelLeft + 3, panelBottom, 0xFF55FFFF);

        int y = panelTop + 8;
        for (String line : lines) {
            if (!line.isEmpty()) {
                graphics.text(font, line, panelLeft + 10, y, 0xFFE6EAF0, true);
            }
            y += 12;
            if (y >= panelBottom - 8) {
                break;
            }
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

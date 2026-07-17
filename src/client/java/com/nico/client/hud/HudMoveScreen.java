package com.nico.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.Locale;

public class HudMoveScreen extends Screen {
    private final HudLayoutManager layoutManager;

    private HudElement dragging;
    private int dragOffsetX;
    private int dragOffsetY;

    protected HudMoveScreen(HudLayoutManager layoutManager) {
        super(Component.literal("Move GUI Elements"));
        this.layoutManager = layoutManager;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tickDelta) {
        super.extractRenderState(graphics, mouseX, mouseY, tickDelta);

        Font font = Minecraft.getInstance().font;

        graphics.text(
                font,
                "Drag GUI elements. Press ESC to save and exit.",
                10,
                10,
                0xFFFFFFFF,
                true
        );

        Collection<HudElement> seenElements = layoutManager.getSeenElements();

        if (seenElements.isEmpty()) {
            graphics.text(
                    font,
                    "You can only move GUI elements you have already seen.",
                    10,
                    24,
                    0xFFAAAAAA,
                    true
            );

            return;
        }

        for (HudElement element : layoutManager.getAll()) {
            drawElementBox(graphics, font, element, mouseX, mouseY);
        }
    }

    private void drawElementBox(GuiGraphicsExtractor graphics, Font font, HudElement element, int mouseX, int mouseY) {
        int x = element.getX();
        int y = element.getY();
        int width = element.getWidth();
        int height = element.getHeight();

        boolean hovered = element.contains(mouseX, mouseY);

        int borderColor = hovered ? 0xFFE0E0E0 : 0xFF888888;
        int backgroundColor = hovered ? 0xAA2A2A2A : 0x88202020;

        graphics.fill(x, y, x + width, y + height, backgroundColor);
        graphics.outline(x, y, width, height, borderColor);

        graphics.text(
                font,
                element.getDisplayName(),
                x + 6,
                y + 6,
                0xFFFFFFFF,
                true
        );

        graphics.text(
                font,
                "x=" + x + " y=" + y,
                x + 6,
                y + 18,
                0xFFAAAAAA,
                true
        );

        graphics.text(
                font,
                "scale=" + String.format(Locale.US, "%.2fx", element.getScale()),
                x + 6,
                y + 30,
                0xFFCCCCCC,
                true
        );

        graphics.text(
                font,
                width + "x" + height,
                x + 6,
                y + 42,
                0xFF999999,
                true
        );
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }

        double mouseX = event.x();
        double mouseY = event.y();

        for (HudElement element : layoutManager.getAll()) {
            if (element.contains(mouseX, mouseY)) {
                dragging = element;
                dragOffsetX = (int) mouseX - element.getX();
                dragOffsetY = (int) mouseY - element.getY();
                return true;
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (dragging == null || event.button() != 0) {
            return super.mouseDragged(event, dx, dy);
        }

        double mouseX = event.x();
        double mouseY = event.y();

        int newX = (int) mouseX - dragOffsetX;
        int newY = (int) mouseY - dragOffsetY;

        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        newX = clamp(newX, 0, screenWidth - dragging.getWidth());
        newY = clamp(newY, 0, screenHeight - dragging.getHeight());

        dragging.setPosition(newX, newY);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && dragging != null) {
            dragging = null;
            layoutManager.save();
            return true;
        }

        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        for (HudElement element : layoutManager.getAll()) {
            if (element.contains(x, y)) {
                element.resizeByScroll(scrollY);
                clampElementToScreen(element);
                layoutManager.save();
                return true;
            }
        }

        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    @Override
    public void removed() {
        layoutManager.save();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void clampElementToScreen(HudElement element) {
        int screenWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int screenHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();

        int x = clamp(element.getX(), 0, screenWidth - element.getWidth());
        int y = clamp(element.getY(), 0, screenHeight - element.getHeight());

        element.setPosition(x, y);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }

        return Math.min(value, max);
    }
}

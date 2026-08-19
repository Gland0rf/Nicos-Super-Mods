package com.nico.client.inventoryLayouts.core;

import com.nico.client.hud.HudElement;
import com.nico.client.hud.HudLayoutManager;
import com.nico.client.inventoryLayouts.render.InventoryLayoutOverlay;
import com.nico.client.inventoryLayouts.render.InventoryLayoutsScreen;
import com.nico.client.inventoryLayouts.storage.InventoryLayoutStorage;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

public class InventoryLayoutsFeature {
    private static final int FALLBACK_INVENTORY_GUI_WIDTH = 176;
    private static final int FALLBACK_INVENTORY_GUI_HEIGHT = 166;
    private static final int DEFAULT_BUTTON_WIDTH = 78;
    private static final int DEFAULT_BUTTON_HEIGHT = 20;

    private static final InventoryLayoutStorage STORAGE = new InventoryLayoutStorage();
    private static final InventoryLayoutManager MANAGER = new InventoryLayoutManager(STORAGE);

    private static HudLayoutManager hudLayoutManager;
    private static boolean initialized;

    private InventoryLayoutsFeature() { }

    public static synchronized void initialize(HudLayoutManager layoutManager) {
        if (layoutManager != null) {
            hudLayoutManager = layoutManager;
        } else if (hudLayoutManager == null) {
            // Keep the no-argument initializer compatible with the HUD editor.
            // This instance reads the same hud_layout.json file and is refreshed
            // whenever the inventory screen is initialized.
            hudLayoutManager = new HudLayoutManager();
        }

        if (initialized) return;

        STORAGE.load();
        ClientTickEvents.END_CLIENT_TICK.register(MANAGER::tick);

        ScreenEvents.AFTER_INIT.register((minecraft, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof InventoryScreen inventoryScreen)) {
                return;
            }

            if (MANAGER.config().enabled) {
                addInventoryLayoutsButton(inventoryScreen);
            }

            // Screen-specific Fabric events are recreated when a screen is initialized.
            // Reattach this listener every time, including when returning from the
            // layouts menu to the same InventoryScreen instance.
            ScreenEvents.afterExtract(screen).register((renderedScreen, graphics, mouseX, mouseY, tickDelta) -> {
                if (renderedScreen instanceof InventoryScreen renderedInventoryScreen) {
                    InventoryLayoutOverlay.render(
                            renderedInventoryScreen,
                            graphics,
                            mouseX,
                            mouseY,
                            MANAGER
                    );
                }
            });
        });

        initialized = true;
        System.out.println("[NSM Inventory Layouts] initialized");
    }

    public static synchronized void initialize() {
        initialize(null);
    }

    public static InventoryLayoutManager manager() {
        return MANAGER;
    }

    private static void addInventoryLayoutsButton(InventoryScreen screen) {
        ButtonBounds bounds = getButtonBounds(screen);

        Screens.getWidgets(screen).add(
                Button.builder(
                                Component.literal(MANAGER.activeLayout() == null ? "Layouts" : "Layouts *"),
                                button -> Minecraft.getInstance().setScreen(
                                        new InventoryLayoutsScreen(screen, MANAGER)
                                )
                        )
                        .bounds(bounds.x(), bounds.y(), bounds.width(), bounds.height())
                        .build()
        );
    }

    private static ButtonBounds getButtonBounds(InventoryScreen screen) {
        if (hudLayoutManager != null) {
            // The HUD editor may use a different manager instance. Reloading here
            // makes the button consume the coordinates most recently saved by it.
            hudLayoutManager.load();

            HudElement element = hudLayoutManager.get(HudLayoutManager.INVENTORY_LAYOUTS_BUTTON);

            if (element != null) {
                if (!element.hasBeenSeen()) {
                    element.markSeen();
                    hudLayoutManager.save();
                }

                int width = Math.max(56, element.getWidth());
                int height = Math.max(18, element.getHeight());
                int x = clamp(element.getX(), 0, Math.max(0, screen.width - width));
                int y = clamp(element.getY(), 0, Math.max(0, screen.height - height));

                return new ButtonBounds(x, y, width, height);
            }
        }

        int left = (screen.width - FALLBACK_INVENTORY_GUI_WIDTH) / 2;
        int top = (screen.height - FALLBACK_INVENTORY_GUI_HEIGHT) / 2;
        int x = left + FALLBACK_INVENTORY_GUI_WIDTH + 4;

        if (x + DEFAULT_BUTTON_WIDTH > screen.width - 4) {
            x = Math.max(4, left - DEFAULT_BUTTON_WIDTH - 4);
        }

        return new ButtonBounds(x, top + 4, DEFAULT_BUTTON_WIDTH, DEFAULT_BUTTON_HEIGHT);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        return Math.min(value, max);
    }

    private record ButtonBounds(int x, int y, int width, int height) { }
}
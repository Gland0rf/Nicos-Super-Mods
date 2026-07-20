package com.nico.client.inventoryLayouts.render;

import com.nico.client.inventoryLayouts.core.InventoryLayout;
import com.nico.client.inventoryLayouts.core.InventoryLayoutManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class SaveInventoryLayoutScreen extends Screen {
    private final Screen menuParent;
    private final Screen inventoryParent;
    private final InventoryLayoutManager manager;

    private EditBox nameField;
    private Component status = Component.empty();

    public SaveInventoryLayoutScreen(
            Screen menuParent,
            Screen inventoryParent,
            InventoryLayoutManager manager
    ) {
        super(Component.literal("Save Inventory Layout"));
        this.menuParent = menuParent;
        this.inventoryParent = inventoryParent;
        this.manager = manager;
    }

    @Override
    protected void init() {
        int centerX = width / 2;

        nameField = new EditBox(
                font,
                centerX - 120,
                height / 2 - 24,
                240,
                20,
                Component.literal("Layout name")
        );
        nameField.setMaxLength(48);
        nameField.setHint(Component.literal("Example: Mage clear"));
        nameField.setFocused(true);
        addRenderableWidget(nameField);

        addRenderableWidget(
                Button.builder(Component.literal("Save"), button -> saveLayout())
                        .bounds(centerX - 102, height / 2 + 10, 96, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.literal("Cancel"), button -> onClose())
                        .bounds(centerX + 6, height / 2 + 10, 96, 20)
                        .build()
        );
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xE0101218);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 60, 0xFF55CCFF);
        graphics.drawCenteredString(
                font,
                "This saves positions only. It never moves any items.",
                width / 2,
                height / 2 - 44,
                0xFFBBBBBB
        );

        if (!status.getString().isEmpty()) {
            graphics.drawCenteredString(font, status, width / 2, height / 2 + 40, 0xFFFF7777);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }


    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ENTER) {
            saveLayout();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(menuParent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void saveLayout() {
        Minecraft minecraft = Minecraft.getInstance();
        String name = nameField == null ? "" : nameField.getValue().trim();

        if (name.isBlank()) {
            status = Component.literal("Enter a layout name.");
            return;
        }

        if (minecraft.player == null) {
            status = Component.literal("No player inventory is available.");
            return;
        }

        InventoryLayout layout = InventoryLayout.capture(name, minecraft.player);
        manager.storage().upsert(layout);
        Minecraft.getInstance().setScreen(new InventoryLayoutsScreen(inventoryParent, manager));
    }
}
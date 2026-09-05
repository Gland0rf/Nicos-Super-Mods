package com.nico.mixin;

import com.nico.client.configuration.NsmConfig;
import com.nico.client.configuration.category.CategoryOther;
import com.nico.client.inventoryLayouts.core.InventoryLayoutsFeature;
import com.nico.client.inventoryLayouts.render.InventoryLayoutOverlay;
import com.nico.client.wiki.screen.WikiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Shadow
    @Nullable
    protected Slot hoveredSlot;

    @Inject(
            method = "keyPressed",
            at = @At("HEAD"),
            cancellable = true
    )
    private void nsm$openWikiWithKeyboard(
            KeyEvent event,
            CallbackInfoReturnable<Boolean> callback
    ) {
        CategoryOther config = NsmConfig.INSTANCE.other;
        if (!config.wiki.wikiShortcutEnabled) {
            return;
        }

        if (!event.hasControlDownWithQuirk()) {
            return;
        }

        if (event.input() != config.wiki.wikiShortcut) {
            return;
        }

        if (nsm$openHoveredItem()) {
            callback.setReturnValue(true);
        }
    }

    @Inject(
            method = "mouseClicked",
            at = @At("HEAD"),
            cancellable = true
    )
    private void nsm$openWikiWithMouse(
            MouseButtonEvent event,
            boolean doubleClick,
            CallbackInfoReturnable<Boolean> callback
    ) {
        CategoryOther config = NsmConfig.INSTANCE.other;

        if (!config.wiki.wikiShortcutEnabled) {
            return;
        }

        if (!event.hasControlDownWithQuirk()) {
            return;
        }

        if (event.input() != config.wiki.wikiShortcut) {
            return;
        }

        if (nsm$openHoveredItem()) {
            callback.setReturnValue(true);
        }
    }

    @Inject(
            method = "extractTooltip",
            at = @At("HEAD"),
            cancellable = true
    )
    private void nsm$replaceInventoryLayoutTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY, CallbackInfo callback) {
        if (!((Object) this instanceof InventoryScreen inventoryScreen)) return;

        if (InventoryLayoutOverlay.captureLayoutTooltip(
                inventoryScreen,
                hoveredSlot,
                mouseX,
                mouseY,
                InventoryLayoutsFeature.manager()
        )) {
            callback.cancel();
        }
    }

    private boolean nsm$openHoveredItem() {
        Slot slot = hoveredSlot;

        if (slot == null || !slot.hasItem()) {
            return false;
        }

        ItemStack stack = slot.getItem();

        if (stack.isEmpty()) {
            return false;
        }

        Minecraft client = Minecraft.getInstance();
        Screen currentScreen = (Screen) (Object) this;

        client.setScreen(
                new WikiScreen(
                        currentScreen,
                        stack.copy()
                )
        );

        return true;
    }
}
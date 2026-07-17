package com.nico.mixin;

import com.nico.client.wiki.WikiScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Shadow
    @Nullable
    protected Slot hoveredSlot;

    @Inject(
            method="mouseClicked",
            at = @At("HEAD"),
            cancellable = true
    )
    private void nsm$openWikiPage(
            MouseButtonEvent event,
            boolean doubleClick,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;

        if (!event.hasControlDownWithQuirk()) return;

        Slot slot = hoveredSlot;

        if (slot == null || !slot.hasItem()) return;

        ItemStack stack = slot.getItem();

        if (stack.isEmpty()) return;

        Minecraft client = Minecraft.getInstance();
        Screen currentScreen = (Screen) (Object) this;

        client.setScreen(
                new WikiScreen(
                        currentScreen,
                        stack.copy()
                )
        );

        callback.setReturnValue(true);
    }

}

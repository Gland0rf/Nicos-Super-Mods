package com.nico.client.wiki.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

/** Public implementation. Most logic lives in focused package-private layers. */
public class WikiScreen extends WikiScreenInput {
    public WikiScreen(Screen parent, ItemStack itemStack) {
        super(parent, itemStack);
    }
}

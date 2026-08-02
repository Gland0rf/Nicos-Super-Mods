package com.nico.client.wiki.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;

import java.net.URI;

public class WikiScreen extends WikiScreenInput {
    public WikiScreen(Screen parent, ItemStack itemStack) {
        super(parent, itemStack);
    }

    @Override
    protected void openCurrentSource() {
        PageTab tab = activeBrowserTab();

        URI source = page != null && page.pageUri() != null
                ? page.pageUri()
                : tab.requestUri;

        if (source != null) {
            openResolvedUri(source, OpenDisposition.EXTERNAL);
        }
    }
}

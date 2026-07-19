package com.nico.client.configuration;

import com.nico.client.configuration.category.CategoryDungeons;
import com.nico.client.configuration.category.CategoryIsland;
import com.nico.client.configuration.category.CategoryOther;
import io.github.notenoughupdates.moulconfig.Config;
import io.github.notenoughupdates.moulconfig.annotations.*;
import io.github.notenoughupdates.moulconfig.common.text.StructuredText;

public class NsmConfig extends Config {

    public static NsmConfig INSTANCE = new NsmConfig();

    @Override
    public StructuredText getTitle() {
        return StructuredText.of("Nico's Super Mods");
    }

    @Override
    public boolean shouldAutoFocusSearchbar() {
        return true;
    }

    @Category(name = "Dungeons", desc = "Dungeon-related features.")
    public CategoryDungeons dungeons = new CategoryDungeons();

    @Category(name = "Island", desc = "Things to help you with on your island")
    public CategoryIsland island = new CategoryIsland();

    /*Category(name = "Other", desc = "Misc features")
    public CategoryOther other = new CategoryOther();*/ // TEMPORARY


}
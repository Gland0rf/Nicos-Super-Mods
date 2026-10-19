package com.nico.client.configuration;

import com.nico.client.configuration.category.*;
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

    @Category(name = "Party", desc = "Party features")
    public CategoryParty party = new CategoryParty();

    @Category(name = "Tools", desc = "Miscellaneous tools for all aspects")
    public CategoryTools tools = new CategoryTools();

    @Category(name = "Performance", desc = "Connection and memory monitoring")
    public CategoryPerformance performance = new CategoryPerformance();

    /** Runtime code accesses stable feature types, never a category path */
    public <T> T feature(Class<T> type) {
        return FeatureRegistry.get(this, type);
    }
}
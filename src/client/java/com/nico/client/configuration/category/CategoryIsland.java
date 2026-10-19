package com.nico.client.configuration.category;

import com.nico.client.configuration.FeatureId;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

public class CategoryIsland {

    @FeatureId(value = "minionInfo")
    @ConfigOption(name = "Minion Profit Overlay", desc = "Shows minion profits, coins per day, and upgrade ROI in minion GUIs.")
    @Accordion
    public FeatureOptions.MinionProfitOverlay minionInfo = new FeatureOptions.MinionProfitOverlay();
}
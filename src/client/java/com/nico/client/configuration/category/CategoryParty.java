package com.nico.client.configuration.category;

import com.nico.client.configuration.FeatureId;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

public class CategoryParty {
    @FeatureId(value = "partySafety")
    @ConfigOption(name = "Party Safety", desc = "Blocks automatic party transfers triggered by a player that just joined")
    @Accordion
    public FeatureOptions.PartySafety partySafety = new FeatureOptions.PartySafety();

    @FeatureId(value = "partyMemory")
    @ConfigOption(name = "Party Memory", desc = "Remembers players you play with and shows a history when they join again.")
    @Accordion
    public FeatureOptions.PartyMemory partyMemory = new FeatureOptions.PartyMemory();
}

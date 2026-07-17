package com.nico.client.configuration.category;

import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean;
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorDropdown;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

public class CategoryIsland {

    @ConfigOption(
            name = "Minion Profit Overlay",
            desc = "Shows estimated minion output, coins per day, and upgrade ROI inside minion GUIs."
    )
    @Accordion
    public MinionProfitOverlay minionInfo = new MinionProfitOverlay();

    public static class MinionProfitOverlay {

        @ConfigOption(
                name = "Enabled",
                desc = "Shows the minion profit overlay while a minion GUI is open."
        )
        @ConfigEditorBoolean
        public boolean enabled = true;

        @ConfigOption(
                name = "Show Upgrade ROI",
                desc = "Shows next-tier upgrade cost, profit gain, and payback time."
        )
        @ConfigEditorBoolean
        public boolean showUpgradeRoi = true;

        @ConfigOption(
                name = "Bazaar Price Mode",
                desc = "Choose whether profit uses instant-sell or instant-buy Bazaar prices."
        )
        @ConfigEditorDropdown(
                values = {
                        "Instant Sell",
                        "Instant Buy"
                }
        )
        public int bazaarPriceMode = 0;
    }
}
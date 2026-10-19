package com.nico.client.configuration.category;

import com.nico.client.configuration.FeatureId;
import io.github.notenoughupdates.moulconfig.annotations.Accordion;
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption;

public class CategoryTools {
    @FeatureId(value = "inventoryLayouts")
    @ConfigOption(name = "Inventory Layouts", desc = "Save inventory layouts and see a guide when rebuilding them.")
    @Accordion
    public FeatureOptions.InventoryLayouts inventoryLayouts = new FeatureOptions.InventoryLayouts();

    @FeatureId(value = "wiki")
    @ConfigOption(name = "Integrated Wiki", desc = "Open the SkyBlock wiki from items without leaving Minecraft.")
    @Accordion
    public FeatureOptions.IntegratedWiki wiki = new FeatureOptions.IntegratedWiki();

    @FeatureId(value = "skyblockWrapped")
    @ConfigOption(name = "SkyBlock Wrapped", desc = "Configure the statistics displayed in your SkyBlock Wrapped.")
    @Accordion
    public FeatureOptions.SkyblockWrapped skyblockWrapped = new FeatureOptions.SkyblockWrapped();
}

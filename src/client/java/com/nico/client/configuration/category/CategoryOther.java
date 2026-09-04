package com.nico.client.configuration.category;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.notenoughupdates.moulconfig.annotations.*;
import org.lwjgl.glfw.GLFW;

public class CategoryOther {

    @ConfigOption(
            name = "Inventory Layouts",
            desc = "Lets you save inventory arrangements and display a visual guide while rebuilding them."
    )
    @Accordion
    public InventoryLayouts inventoryLayouts = new InventoryLayouts();

    @ConfigOption(
            name = "Integrated Wiki",
            desc = "Open up the skyblock wiki inside minecraft, so you don't have to tab out all the time."
    )
    @Accordion
    public IntegratedWiki wiki = new IntegratedWiki();

    @ConfigOption(
            name = "Memory Leak Detector",
            desc = "Detects sustained heap growth and identifies likely mod allocation sources."
    )
    @Accordion
    public MemLeak memLeak = new MemLeak();

    public static class IntegratedWiki {
        @ConfigOption(
                name = "Enable",
                desc = "Allows opening the Wiki from inventory items."
        )
        @ConfigEditorBoolean
        public boolean wikiShortcutEnabled = true;

        @ConfigOption(
                name = "Wiki Shortcut",
                desc = "The key or mouse button used with Ctrl."
        )
        @ConfigEditorKeybind(defaultKey = GLFW.GLFW_MOUSE_BUTTON_RIGHT)
        public int wikiShortcut = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    }

    public static class InventoryLayouts {
        @ConfigOption(
                name = "Enable",
                desc = "Enable this mod"
        )
        @ConfigEditorBoolean
        public boolean enabled = true;

        @ConfigOption(
                name = "Auto-finish Layout",
                desc = "Automatically stops the active layout once every inventory slot matches."
        )
        @ConfigEditorBoolean
        public boolean autoDisableWhenComplete = true;

        @ConfigOption(
                name = "Highlight Correct Slots",
                desc = "Shows a green overlay on slots that already contain the correct item."
        )
        @ConfigEditorBoolean
        public boolean showCorrectSlots = true;

        @ConfigOption(
                name = "Match Stack Counts",
                desc = "Requires item stack sizes to match the saved layout exactly."
        )
        @ConfigEditorBoolean
        public boolean matchStackCounts = false;
    }

    public static class MemLeak {

        @ConfigOption(
                name = "Enable",
                desc = "Monitor memory usage and automatically attribute allocations to loaded mods.\n"
                        + "§cRequires a restart after changing."
        )
        @ConfigEditorBoolean
        public boolean enabled = true;

        @ConfigOption(
                name = "Chat Alerts",
                desc = "Show a chat warning when sustained heap growth is detected."
        )
        @ConfigEditorBoolean
        public boolean chatAlerts = true;

        @ConfigOption(
                name = "Analysis Window",
                desc = "Number of recent minutes used when calculating the memory trend."
        )
        @ConfigEditorSlider(
                minValue = 10,
                maxValue = 120,
                minStep = 5
        )
        public int windowMinutes = 30;

        @ConfigOption(
                name = "Minimum Observation Time (minutes)",
                desc = "How long MemLeak must observe memory before issuing a warning."
        )
        @ConfigEditorSlider(
                minValue = 5,
                maxValue = 60,
                minStep = 5
        )
        public int minimumObservationMinutes = 10;

        @ConfigOption(
                name = "Minimum GC Samples",
                desc = "Minimum number of major garbage collections required before evaluating the trend."
        )
        @ConfigEditorSlider(
                minValue = 3,
                maxValue = 20,
                minStep = 1
        )
        public int minimumSamples = 6;

        @ConfigOption(
                name = "Minimum Memory Growth",
                desc = "Required post-GC heap growth before it is considered suspicious."
        )
        @ConfigEditorSlider(
                minValue = 32,
                maxValue = 1024,
                minStep = 32
        )
        public int minimumGrowthMiB = 128;

        @ConfigOption(
                name = "Minimum Growth Rate",
                desc = "Required memory growth per minute before an alert is generated."
        )
        @ConfigEditorSlider(
                minValue = 1,
                maxValue = 128,
                minStep = 1
        )
        public int minimumGrowthMiBPerMinute = 8;
    }

}

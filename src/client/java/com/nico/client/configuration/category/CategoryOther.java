package com.nico.client.configuration.category;

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
            name = "ModCheck",
            desc = "Configure the installed mod integrity checker."
    )
    @Accordion
    public ModCheck modCheck = new ModCheck();

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

    public static class ModCheck {
        @ConfigOption(
                name = "Enable ModCheck",
                desc = "Enables scanning installed mod JARs against the signed trust registry."
        )
        @ConfigEditorBoolean
        public boolean enabled = true;

        @ConfigOption(
                name = "Show Warning Screen",
                desc = "Automatically opens the warning screen when critical or unknown files are found."
        )
        @ConfigEditorBoolean
        public boolean showWarningScreen = true;

        @ConfigOption(
                name = "Warn About Unknown Mods",
                desc = "Shows warnings for exact mod files that are not present in the trust registry."
        )
        @ConfigEditorBoolean
        public boolean warnAboutUnknownMods = true;
    }

}

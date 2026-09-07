package com.nico.client.configuration.category;

import io.github.notenoughupdates.moulconfig.annotations.*;
import org.lwjgl.glfw.GLFW;

public class CategoryOther {

    @ConfigOption(
            name = "Lag Monitor",
            desc = "Tracks TPS, ping, jitter, and connection stalls with a customizable HUD."
    )
    @Accordion
    public LagMonitor lagMonitor = new LagMonitor();

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

    public static class LagMonitor {
        @ConfigOption(
                name = "Enabled",
                desc = "Tracks TPS, ping, jitter and connection stalls."
        )
        @ConfigEditorBoolean
        public boolean enabled = true;

        @ConfigOption(
                name = "Show HUD",
                desc = "Displays the Lag Monitor HUD when the current location is enabled below."
        )
        @ConfigEditorBoolean
        public boolean showHud = true;

        @ConfigOption(
                name = "Visibility",
                desc = "Choose where the Lag Monitor is active."
        )
        @Accordion
        public Visibility visibility = new Visibility();

        @ConfigOption(
                name = "Design",
                desc = "Customize the Lag Monitor HUD appearance and which values are shown."
        )
        @Accordion
        public Design design = new Design();

        @ConfigOption(
                name = "Show Warning Titles",
                desc = "Shows title alerts for low TPS, high ping, and connection stalls."
        )
        @ConfigEditorBoolean
        public boolean showTitles = true;

        @ConfigOption(
                name = "Show End Report",
                desc = "Shows a lag report when your dungeon run ends. §4Requires Dungeons visibility to be enabled."
        )
        @ConfigEditorBoolean
        public boolean showEndReport = true;

        @ConfigOption(
                name = "Copy TPS Loss",
                desc = "Copies the estimated TPS time loss to your clipboard after a dungeon run."
        )
        @ConfigEditorBoolean
        public boolean copyTpsLossToClipboard = true;

        public static class Visibility {
            @ConfigOption(
                    name = "Dungeons",
                    desc = "Show and sample the Lag Monitor while you are inside a dungeon."
            )
            @ConfigEditorBoolean
            public boolean showInDungeons = true;

            @ConfigOption(
                    name = "Hypixel Outside Dungeons",
                    desc = "Show and sample the Lag Monitor on Hypixel when you are not inside a dungeon."
            )
            @ConfigEditorBoolean
            public boolean showOnHypixelOutsideDungeons = true;

            @ConfigOption(
                    name = "Other Multiplayer Servers",
                    desc = "Show and sample the Lag Monitor on non-Hypixel multiplayer servers."
            )
            @ConfigEditorBoolean
            public boolean showOnOtherServers = false;
        }

        public static class Design {
            @ConfigOption(
                    name = "Background",
                    desc = "Draw the dark background behind the Lag Monitor."
            )
            @ConfigEditorBoolean
            public boolean showBackground = true;

            @ConfigOption(
                    name = "Background Opacity",
                    desc = "Opacity of the HUD background when Background is enabled."
            )
            @ConfigEditorSlider(
                    minValue = 0,
                    maxValue = 100,
                    minStep = 1
            )
            public int backgroundOpacity = 69;

            @ConfigOption(
                    name = "Accent Bar",
                    desc = "Draw the colored lag-status bar on the left side."
            )
            @ConfigEditorBoolean
            public boolean showAccentBar = true;

            @ConfigOption(
                    name = "Header",
                    desc = "Show the 'Lag Monitor' header."
            )
            @ConfigEditorBoolean
            public boolean showHeader = true;

            @ConfigOption(
                    name = "TPS",
                    desc = "Show the estimated server TPS."
            )
            @ConfigEditorBoolean
            public boolean showTps = true;

            @ConfigOption(
                    name = "Ping",
                    desc = "Show your current connection ping."
            )
            @ConfigEditorBoolean
            public boolean showPing = true;

            @ConfigOption(
                    name = "Jitter",
                    desc = "Show ping variation (jitter)."
            )
            @ConfigEditorBoolean
            public boolean showJitter = true;

            @ConfigOption(
                    name = "Packet Gap",
                    desc = "Show packet-gap time when the connection stalls for at least 0.5 seconds."
            )
            @ConfigEditorBoolean
            public boolean showPacketGap = true;

            @ConfigOption(
                    name = "Diagnosis",
                    desc = "Show the current lag diagnosis, such as Server Lag or Network Lag."
            )
            @ConfigEditorBoolean
            public boolean showDiagnosis = true;

            @ConfigOption(
                    name = "Text Shadow",
                    desc = "Draw a shadow behind HUD text."
            )
            @ConfigEditorBoolean
            public boolean textShadow = true;
        }
    }

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
                desc = "Watch for memory that keeps growing and suggest likely causes.\n"
                        + "§cRequires a restart after changing."
        )
        @ConfigEditorBoolean
        public boolean enabled = true;

        @ConfigOption(
                name = "Chat Alerts",
                desc = "Show a chat warning when sustained memory growth is detected."
        )
        @ConfigEditorBoolean
        public boolean chatAlerts = true;

        @ConfigOption(
                name = "Auto-clean Temporary NSM Data",
                desc = "Clear temporary dungeon(player tracking when changing or leaving world.\n"
                        + "§7Keeps wiki caches, PBs, routes, layouts, and settings."
        )
        @ConfigEditorBoolean
        public boolean autoCleanupTransientData = true;

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

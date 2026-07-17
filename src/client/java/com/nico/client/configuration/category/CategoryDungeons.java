package com.nico.client.configuration.category;

import com.nico.client.secretTimer.SecretRoomTimerClient;
import io.github.notenoughupdates.moulconfig.annotations.*;

public class CategoryDungeons {
    @ConfigOption(
            name = "Secret Stacking Detector",
            desc = "Detects when you and another player are likely doing secrets in the same room."
    )
    @ConfigEditorBoolean
    public boolean secretStackingDetectorEnabled = false;

    @ConfigOption(
            name = "Room Stacking Detector",
            desc = "Detects when two or more dungeon teammates are in the same room."
    )
    @Accordion
    public RoomStacking roomStacking = new RoomStacking();

    @ConfigOption(
            name = "Room Secret Timer",
            desc = "Times how long it takes you to personally complete all secrets in a room, alone."
    )
    @Accordion
    public SecretRoomTimer secretRoomTimer = new SecretRoomTimer();

    @ConfigOption(
            name = "Goldor Terminal Highlighter",
            desc = "Highlights your designated terminals in f7/m7"
    )
    @Accordion
    public GoldorTerminal goldorTerminal = new GoldorTerminal();

    public static class RoomStacking {

        @ConfigOption(
                name = "Enabled",
                desc = "Detects when two or more dungeon teammates are in the same room."
        )
        @ConfigEditorBoolean
        public boolean enabled = false;

        @ConfigOption(
                name = "Self Title Alert",
                desc = "Shows a title alert when you are stacking."
        )
        @ConfigEditorBoolean
        public boolean showSelfTitleAlert = true;

        @ConfigOption(
                name = "Other Players Chat Alert",
                desc = "Sends a chat message when other players are stacking."
        )
        @ConfigEditorBoolean
        public boolean showOtherStackingChatAlert = true;

        @ConfigOption(
                name = "Alert Sounds",
                desc = "Plays a sound when stacking is detected."
        )
        @ConfigEditorBoolean
        public boolean playAlertSounds = true;

        @ConfigOption(
                name = "Include Classes In Chat",
                desc = "Shows dungeon classes next to player names in chat alerts."
        )
        @ConfigEditorBoolean
        public boolean includeClassesInChat = true;

        @ConfigOption(
                name = "Ignore Fairy",
                desc = "Does not alert for players stacked in Fairy room."
        )
        @ConfigEditorBoolean
        public boolean ignoreFairy = true;

        @ConfigOption(
                name = "Ignore Blood",
                desc = "Does not alert for players stacked in Blood room."
        )
        @ConfigEditorBoolean
        public boolean ignoreBlood = true;

        @ConfigOption(
                name = "Disable At Secret Percent",
                desc = "Room stacking alerts stop after this total secret percentage."
        )
        @ConfigEditorSlider(
                minValue = 0,
                maxValue = 100,
                minStep = 1
        )
        public int disableRoomStackingAtSecretPercent = 80;
    }

    public static class SecretRoomTimer {

        @ConfigOption(
                name = "Enabled",
                desc = "Times your personal full-secret clears per dungeon room and saves PBs."
        )
        @ConfigEditorBoolean
        public boolean enabled = false;

        @ConfigOption(
                name = "Show Start Message",
                desc = "Sends a chat message when a room timer starts."
        )
        @ConfigEditorBoolean
        public boolean showStartMessage = false;

        @ConfigOption(
                name = "Show Progress Messages",
                desc = "Sends chat progress like 2/5 secrets."
        )
        @ConfigEditorBoolean
        public boolean showProgressMessages = true;

        @ConfigOption(
                name = "Show Completion Message",
                desc = "Sends a chat message when you complete all secrets in a room."
        )
        @ConfigEditorBoolean
        public boolean showCompletionMessage = true;

        @ConfigOption(
                name = "Only Announce New PBs",
                desc = "If enabled, only sends a completion message when the time is a new PB."
        )
        @ConfigEditorBoolean
        public boolean onlyAnnounceNewPbs = false;

        @ConfigOption(
                name = "Display All PBs",
                desc = "Prints all saved secret room PBs in chat."
        )
        @ConfigEditorButton(buttonText = "Display")
        public transient Runnable displayAllPbs = SecretRoomTimerClient::displayAllPbs;

        @ConfigOption(
                name = "Reset Current Room PB",
                desc = "Deletes the saved PB for your current dungeon room."
        )
        @ConfigEditorButton(buttonText = "Reset Current")
        public transient Runnable resetCurrentRoomPb = SecretRoomTimerClient::resetCurrentRoomPb;

        @ConfigOption(
                name = "Reset All PBs",
                desc = "Deletes every saved secret room PB."
        )
        @ConfigEditorButton(buttonText = "Reset All")
        public transient Runnable resetAllPbs = SecretRoomTimerClient::resetAllPbs;
    }


    public static class GoldorTerminal {

        @ConfigOption(
                name = "Enabled",
                desc = "Highlights your designated terminals in f7/m7"
        )
        @ConfigEditorBoolean
        public boolean enabled = false;
    }
}
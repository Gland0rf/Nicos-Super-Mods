package com.nico.client.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.nico.client.utils.AtomicFiles;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.awt.*;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public final class WrappedConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("nicos_super_mods")
            .resolve("wrapped_config.json");

    private WrappedConfig() { }

    public static Path path() {
        return FILE;
    }

    public static synchronized Settings load() {
        if (!Files.exists(FILE)) {
            Settings defaults = new Settings();
            save(defaults);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Settings settings = GSON.fromJson(reader, Settings.class);
            if (settings == null) settings = new Settings();
            settings.normalize();
            // Re-save after normalization so newly-added display toggles appear in existing files.
            save(settings);
            return settings;
        } catch (IOException | RuntimeException exception) {
            System.err.println("[NSM Wrapped] Could not load display config; using defaults: " + exception.getMessage());
            return new Settings();
        }
    }

    public static synchronized void ensureExists() {
        if (!Files.exists(FILE)) save(new Settings());
    }

    public static synchronized void save(Settings settings) {
        Settings safe = settings == null ? new Settings() : settings;
        safe.normalize();
        try {
            AtomicFiles.writeAtomically(FILE, temporary -> {
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    GSON.toJson(safe, writer);
                }
            });
        } catch (IOException exception) {
            System.err.println("[NSM Wrapped] Could not save display config: " + exception.getMessage());
        }
    }

    public static void openConfigFile() {
        ensureExists();
        try {
            if (!Desktop.isDesktopSupported()) throw new UnsupportedOperationException("Desktop integration is unavailable");
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) throw new UnsupportedOperationException("Opening files is unavailable.");
            desktop.open(FILE.toFile());
        } catch (IOException | RuntimeException exception) {
            System.err.println("[NSM Wrapped] Could not open config file: " + exception.getMessage());
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> {
                minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal(
                        "§e[NSM Wrapped] Could not open the file automatically. Edit §f" + FILE.toAbsolutePath()
                ));
            });
        }
    }

    public static final class Settings {
        public String _readMe= "These settings only control what Skyblock Wrapped SHOWS. History keeps collecting while a section is hidden so you can turn it back on later.";
        public String _partyMemoryNote = "Party Memory is intentionally not configured here. Toggle it in the in-game config. When Party Memory is disabled, it stops tracking player memory and every Party-Memory derived stat is hidden from Wrapped, even if its show toggle below is true.";

        public boolean enabled = true;
        public Time time = new Time();
        public Financials financials = new Financials();
        public MostUsed mostUsed = new MostUsed();
        public Dungeons dungeons = new Dungeons();
        public Slayers slayers = new Slayers();
        public Mining mining = new Mining();
        public Farming farming = new Farming();
        public Fishing fishing = new Fishing();
        public Foraging foraging = new Foraging();
        public Kuudra kuudra = new Kuudra();
        public TimedEvents timedEvents = new TimedEvents();
        public Rift rift = new Rift();
        public Other other = new Other();

        void normalize() {
            if (_readMe == null || _readMe.isBlank()) {
                _readMe = new Settings()._readMe;
            }
            if (_partyMemoryNote == null || _partyMemoryNote.isBlank()) {
                _partyMemoryNote = new Settings()._partyMemoryNote;
            }
            if (time == null) time = new Time();
            if (financials == null) financials = new Financials();
            if (mostUsed == null) mostUsed = new MostUsed();
            if (dungeons == null) dungeons = new Dungeons();
            if (slayers == null) slayers = new Slayers();
            if (mining == null) mining = new Mining();
            if (farming == null) farming = new Farming();
            if (fishing == null) fishing = new Fishing();
            if (foraging == null) foraging = new Foraging();
            if (kuudra == null) kuudra = new Kuudra();
            if (timedEvents == null) timedEvents = new TimedEvents();
            if (rift == null) rift = new Rift();
            if (other == null) other = new Other();
        }
    }

    public static final class Time {
        public boolean enabled = true;
        public boolean playtime = true;
        public boolean numberOfSessions = true;
        public boolean longestSession = true;
        public boolean averageSessionLength = true;
        public boolean daysPlayedHeatmap = true;
    }

    public static final class Financials {
        public boolean enabled = true;
        public boolean coinsEarned = true;
        public boolean coinsSpent = true;
        public boolean netCoinChange = true;
        public boolean mostExpensiveBuy = true;
        public boolean taxesPaid = true;
    }

    public static final class MostUsed {
        public boolean enabled = true;
        public boolean armorSet = true;
        public boolean bestFriendPet = true;
        public boolean item = true;
    }

    public static final class Dungeons {
        public boolean enabled = true;
        public boolean runs = true;
        public boolean failedRuns = true;
        public boolean deaths = true;
        public boolean mostPlayedFloor = true;
        public boolean uniquePlayersPlayedWith = true;
        public boolean playerPlayedWithMost = true;
        public boolean reunion = true;
        public boolean fastestRun = true;
    }

    public static final class Slayers {
        public boolean enabled = true;
        public boolean bossesKilled = true;
        public boolean mostPlayedSlayer = true;
        public boolean fastestBoss = true;
        public boolean rngDrops = true;
    }

    public static final class Mining {
        public boolean enabled = true;
        public boolean mostMinedResource = true;
        public boolean commissionsCompleted = true;
        public boolean forgeWorkingTime = true;
    }

    public static final class Farming {
        public boolean enabled = true;
        public boolean cropsBroken = true;
        public boolean mostFarmedCrop = true;
        public boolean jacobContestsEntered = true;
        public boolean pestsKilled = true;
    }

    public static final class Fishing {
        public boolean enabled = true;
        public boolean preferredWaterOrLava = true;
        public boolean seaCreaturesKilled = true;
        public boolean rareSeaCreatureCount = true;
        public boolean deaths = true;
    }

    public static final class Foraging {
        public boolean enabled = true;
        public boolean logsBroken = true;
        public boolean mostChoppedWood = true;
    }

    public static final class Kuudra {
        public boolean enabled = true;
        public boolean runs = true;
        public boolean failedRuns = true;
        public boolean favoriteTier = true;
        public boolean fastestRun = true;
        public boolean slowestRun = true;
    }

    public static final class TimedEvents {
        public boolean enabled = true;
        public boolean eventsParticipatedIn = true;
    }

    public static final class Rift {
        public boolean enabled = true;
        public boolean motesEarned = true;
        public boolean motesSpent = true;
        public boolean deathsByMob = true;
    }

    public static final class Other {
        public boolean enabled = true;
        public boolean uniquePlayersEncountered = true;
        public boolean recurringPlayers = true;
        public boolean regularPlayers = true;
        public boolean playerSeenMost = true;
        public boolean dungeonPartner = true;
        public boolean reunion = true;
        public boolean chatMessagesSent = true;
    }
}

package com.nico.client.history;

import com.nico.client.configuration.NsmConfig;
import com.nico.client.dungeon.DungeonTeammateScanner;
import com.nico.client.utils.LocationUtils;
import com.nico.client.utils.SkyblockItemResolver;
import com.nico.client.history.PlayerMemoryStore.PlayerMemory;
import com.nico.client.history.PlayerMemoryStore.PreviousEncounter;
import com.nico.client.history.WrappedStore.DayStats;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SkyblockHistoryFeature {
    private static final WrappedStore WRAPPED = new WrappedStore();
    private static final PlayerMemoryStore PLAYER_MEMORY = new PlayerMemoryStore();

    // Hypixel Skyblock epoch: Early Spring 1st, Year 1 at 2019-06-11 17:55 UTC
    // One Skyblock day is 20 real minutes and one year is 372 Skyblock days.
    private static final long SKYBLOCK_EPOCH_MILLIS = 1_560_275_700_000L;
    private static final long SKYBLOCK_DAY_MILLIS = 20L * 60_000L;
    private static final long SKYBLOCK_YEAR_MILLIS = 372L * SKYBLOCK_DAY_MILLIS;

    private static final Pattern USERNAME_AT_END = Pattern.compile("([A-Za-z0-9_]{1,16})$");
    private static final Pattern JOINED_EXISTING_PARTY = Pattern.compile(
                  "(?i)^you have joined\\s+(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16})['’]s party!$"
    );
    private static final Pattern PARTY_CHAT_SENDER = Pattern.compile(
            "(?i)^party\\s*>\\s*(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16})\\s*:"
    );
    private static final Pattern PARTY_MEMBER_LEFT = Pattern.compile(
            "(?i)^(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16})\\s+(?:left the party\\.?|has left the party\\.?|has been removed from the party\\.?|was removed from your party.*|was kicked from the party.*|has been kicked from the party.*)$"
    );

    private static final Pattern COINS = Pattern.compile("(?i)([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt])?\\s+coins?");
    private static final Pattern PURSE = Pattern.compile("(?i)\\b(?:purse|piggy)\\s*:\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kmbt])?");
    private static final Pattern MOTES = Pattern.compile("(?i)\\bmotes?\\s*:?\\s*([0-9][0-9,]*)");
    private static final Pattern PET = Pattern.compile(
            "(?i)(?:you summoned your|autopet equipped your|you equipped your)\\s+(?:\\[lvl\\s+\\d+])?\\s*(.+?)(?:!|\\.)?$"
    );
    private static final Pattern DUNGEON_FLOOR = Pattern.compile(
            "(?i)(?:\\(([FM]\\d+)\\)|\\bfloor\\s+([IVXLCDM]+|[FM]\\d+))"
    );

    private static final Map<String, String> ROMAN_FLOORS = Map.ofEntries(
            Map.entry("I", "F1"), Map.entry("II", "F2"), Map.entry("III", "F3"),
            Map.entry("IV", "F4"), Map.entry("V", "F5"), Map.entry("VI", "F6"),
            Map.entry("VII", "F7")
    );

    private static final Set<String> CROP_BLOCKS = Set.of(
            "wheat", "carrots", "potatoes", "nether_wart", "cocoa", "cactus",
            "sugar_cane", "melon", "pumpkin", "brown_mushroom", "red_mushroom",
            "sunflower", "rose_bush"
    );

    private static final Set<String> MINING_AREAS = Set.of(
            "Gold Mine", "Deep Caverns", "Dwarven Mines", "Crystal Hollows", "Mineshaft"
    );

    private static boolean initialized;
    private static int tickCounter;
    private static int saveCounter;
    private static boolean sessionActive;
    private static long sessionSeconds;
    private static String currentPet = "";
    private static LocationUtils.Island previousArea = LocationUtils.Island.UNKNOWN;

    private static long dungeonStartMillis;
    private static String dungeonFloor = "Unknown";
    private static final Set<String> dungeonTeammates = new LinkedHashSet<>();
    private static final Set<String> currentPartyMembers = new HashSet<>();

    private static String currentSlayer = "";
    private static long slayerBossStartMillis;
    private static long lastSlayerCompleteMillis;
    private static String lastCompletedSlayer = "";

    private static long kuudraStartMillis;
    private static String currentKuudraTier = "";

    private static int lastObservedPestCount = -1;

    private static long currentMotes = -1L;

    private static final long PURSE_IGNORE_WINDOW_MILLIS = 3_000L;
    private static long lastObservedPurse = -1L;
    private static boolean purseTrackingSeen;
    private static long pendingIgnoredPurseAmount;
    private static int pendingIgnoredPurseDirection;
    private static long pendingIgnoredPurseUntilMillis;
    private static long lastPurseDeltaAmount;
    private static int lastPurseDeltaDirection;
    private static long lastPurseDeltaMillis;
    private static boolean lastPurseDeltaCounted;

    private static final Set<String> runtimeEventInstances = new HashSet<>();

    private SkyblockHistoryFeature() { }

    public static synchronized void initialize() {
        if (initialized) return;

        WrappedConfig.ensureExists();
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onGameMessage(message));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onDisconnect());

        initialized = true;
        System.out.println("[NSM History] Party Memory + Skyblock Wrapped initialized");
    }

    public static WrappedStore.WrappedSnapshot snapshot(int days) {
        return WRAPPED.snapshot(days);
    }

    public static WrappedStore.WrappedSnapshot snapshot(YearMonth month) {
        return WRAPPED.snapshot(month);
    }

    public static List<YearMonth> completedWrappedMonths() {
        return WRAPPED.completedMonths();
    }

    public static PlayerMemory playerMemory(String playerName) {
        return PLAYER_MEMORY.get(playerName);
    }

    public static boolean isPartyMemoryEnabled() {
        return partyMemoryEnabled();
    }

    public static void onOutgoingChat() {
        if (!tracking()) return;
        WRAPPED.today().chatMessagesSent++;
        WRAPPED.markDirty();
    }

    public static void onHudText(String rawText) {
        if (!tracking() || rawText == null || rawText.isBlank()) return;
        Matcher matcher = PURSE.matcher(clean(rawText));
        if (!matcher.find()) return;

        long purse = parseCompactNumber(matcher.group(1), matcher.group(2));
        observePurse(purse, System.currentTimeMillis());
    }

    public static void onBlockBreakAttempt(BlockPos pos) {
        if (!tracking() || pos == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return;

        String block = BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(pos).getBlock()).getPath();
        if (block == null || block.isBlank()) return;

        DayStats day = WRAPPED.today();
        LocationUtils.Island area = LocationUtils.getCurrentArea();

        String crop = cropName(block, minecraft);
        if (crop != null && (area == LocationUtils.Island.GARDEN || area == LocationUtils.Island.FARMING_ISLAND)) {
            day.cropsBroken++;
            day.farmedCrops.merge(crop, 1L, Long::sum);
        }

        String wood = woodName(block, area);
        if (wood != null && (area == LocationUtils.Island.THE_PARK || area == LocationUtils.Island.GALATEA || area == LocationUtils.Island.THORRUS_CANYON)) {
            day.logsBroken++;
            day.choppedWoods.merge(wood, 1L, Long::sum);
        }

        if (MINING_AREAS.contains(area.displayName)) {
            String resource = miningResourceName(block);
            if (resource != null) day.minedResources.merge(resource, 1L, Long::sum);
        }
        WRAPPED.markDirty();
    }

    /** Called by DungeonRunPacketDetector so title-based end markers are captured too */
    public static void onDungeonRunStart() {
        if (!tracking() || dungeonStartMillis > 0) return;
        dungeonStartMillis = System.currentTimeMillis();
        dungeonFloor = "Unknown";
        dungeonTeammates.clear();
        sampleDungeonPlayers(Minecraft.getInstance());
    }

    /** Called once per logical run */
    public static void onDungeonRunEnd(boolean failed) {
        if (dungeonStartMillis <= 0) return;

        long now = System.currentTimeMillis();
        long durationSeconds = Math.max(1L, (now - dungeonStartMillis) / 1000L);
        DayStats day = WRAPPED.today();
        day.dungeonRuns++;
        if (failed) day.dungeonFailedRuns++;
        day.fastestDungeonSeconds = minPositive(day.fastestDungeonSeconds, durationSeconds);
        day.slowestDungeonSeconds = Math.max(day.slowestDungeonSeconds, durationSeconds);
        day.dungeonFloorRuns.merge(dungeonFloor, 1, Integer::sum);

        if (partyMemoryEnabled()) {
            Minecraft minecraft = Minecraft.getInstance();
            String self = minecraft.player == null ? "" : minecraft.player.getName().getString();
            for (String teammate : dungeonTeammates) {
                if (teammate.equalsIgnoreCase(self)) continue;
                day.dungeonTeammateRuns.merge(teammate, 1, Integer::sum);
                day.encounteredPlayers.merge(teammate, 1, Integer::sum);
            }
            PLAYER_MEMORY.recordDungeonRun(dungeonTeammates, now);
        }
        WRAPPED.markDirty();

        dungeonStartMillis = 0L;
        dungeonFloor = "Unknown";
        dungeonTeammates.clear();
    }

    public static void onTitle(Component component) {
        if (!tracking() || component == null || LocationUtils.getCurrentArea() != LocationUtils.Island.KUUDRA) return;
        String text = clean(component.getString()).toLowerCase(Locale.ROOT);
        if (text.equals("victory") || text.equals("victory!")) finishKuudra(false);
        else if (text.equals("defeat") || text.equals("defeat!")) finishKuudra(true);
    }

    private static void tick(Minecraft minecraft) {
        tickCounter++;
        if (tickCounter % 20 != 0) return;

        boolean isInSkyblock = LocationUtils.isInSkyblock() && minecraft.player != null && minecraft.level != null;
        if (isInSkyblock) {
            if (!sessionActive) startSession();
            sampleSecond(minecraft);
        } else if (sessionActive) {
            sessionActive = false;
            sessionSeconds = 0L;
            previousArea = LocationUtils.Island.UNKNOWN;
        }

        saveCounter++;
        if (saveCounter >= 30) {
            saveCounter = 0;
            WRAPPED.saveIfDirty();
            PLAYER_MEMORY.saveIfDirty();
        }
    }

    private static void startSession() {
        sessionActive = true;
        sessionSeconds = 0L;
        previousArea = LocationUtils.Island.UNKNOWN;
        DayStats day = WRAPPED.today();
        day.sessions++;
        WRAPPED.markDirty();
    }

    private static void sampleSecond(Minecraft minecraft) {
        DayStats day = WRAPPED.today();
        sessionSeconds++;
        day.playtimeSeconds++;
        day.longestSessionSeconds = Math.max(day.longestSessionSeconds, sessionSeconds);

        LocationUtils.Island area = LocationUtils.getCurrentArea();
        if (area != LocationUtils.Island.UNKNOWN && area != LocationUtils.Island.SINGLE_PLAYER) {
            day.areaSeconds.merge(area.displayName, 1L, Long::sum);
        }
        observeAreaTransition(area);

        ItemStack held = minecraft.player.getMainHandItem();
        String heldName = itemName(held);
        if (!heldName.isBlank()) day.itemSeconds.merge(heldName, 1L, Long::sum);

        String armor = armorSetName(minecraft.player);
        if (!armor.isBlank()) day.armorSetSeconds.merge(armor, 1L, Long::sum);

        String pet = detectPetFromTab(minecraft);
        if (!pet.isBlank()) currentPet = pet;
        if (!currentPet.isBlank()) day.petSeconds.merge(currentPet, 1L, Long::sum);

        sampleFishing(day, heldName, area);
        sampleTabSignals(minecraft, area);
        if (partyMemoryEnabled() && (area == LocationUtils.Island.DUNGEON || dungeonStartMillis > 0)) {
            sampleDungeonPlayers(minecraft);
        }

        WRAPPED.markDirty();
    }

    private static void observeAreaTransition(LocationUtils.Island area) {
        if (area == previousArea) return;
        long now = System.currentTimeMillis();

        if (area == LocationUtils.Island.DARK_AUCTION) recordEventInstance("Dark Auction", darkAuctionInstanceId(now));
        if (area == LocationUtils.Island.KUUDRA && previousArea != LocationUtils.Island.KUUDRA) kuudraStartMillis = now;
        if (previousArea == LocationUtils.Island.KUUDRA && area != LocationUtils.Island.KUUDRA) {
            kuudraStartMillis = 0L;
            currentKuudraTier = "";
        }
        if (area != LocationUtils.Island.RIFT) currentMotes = -1L;
        if (area != LocationUtils.Island.GARDEN) lastObservedPestCount = -1;

        previousArea = area;
    }

    private static void sampleDungeonPlayers(Minecraft minecraft) {
        if (!partyMemoryEnabled() || minecraft.player == null || minecraft.level == null) return;
        String self = minecraft.player.getName().getString();
        Set<String> teammates = DungeonTeammateScanner.getDungeonTeammateNames();
        long now = System.currentTimeMillis();

        for (String teammate : teammates) {
            if (teammate == null || teammate.isBlank() || teammate.equalsIgnoreCase(self)) continue;
            dungeonTeammates.add(teammate);
            PLAYER_MEMORY.observePlayer(teammate, now);

            DayStats day = WRAPPED.today();
            if (!day.encounteredPlayers.containsKey(teammate)) {
                day.encounteredPlayers.put(teammate, 1);
                WRAPPED.markDirty();
            }

            for (Player player : minecraft.level.players()) {
                if (!player.getName().getString().equalsIgnoreCase(teammate)) continue;
                String clazz = DungeonTeammateScanner.getDungeonClassForPlayer(player);
                PLAYER_MEMORY.recordDungeonClassSample(teammate, clazz);
                break;
            }
        }
    }

    private static void onGameMessage(Component component) {
        if (!tracking() || component == null) return;
        String text = clean(component.getString());
        if (text.isBlank()) return;

        long now = System.currentTimeMillis();
        if (partyMemoryEnabled()) parsePartyMemory(text, now);
        else currentPartyMembers.clear();
        parsePet(text);
        parseKuudraTierHint(text);
        parseFinancials(text);
        parseDungeon(text);
        parseSlayer(text, now);
        parseActivityMessages(text, now);
        parseRiftMotes(text);
        parseDeath(text);
    }

    private static void parsePartyMemory(String text, long now) {
        if (!partyMemoryEnabled()) return;
        String lower = text.toLowerCase(Locale.ROOT);
        Matcher joinedExisting = JOINED_EXISTING_PARTY.matcher(text);
        if (joinedExisting.matches()) {
            currentPartyMembers.clear();
            trackPartyMember(joinedExisting.group(1), now);
            return;
        }

        String rosterPrefix = lower.startsWith("you'll be partying with:")
                ? "you'll be partying with:"
                : lower.startsWith("you’ll be partying with:") ? "you’ll be partying with:" : "";
        if (!rosterPrefix.isBlank()) {
            String roster = text.substring(rosterPrefix.length()).trim();
            for (String rawMember : roster.split(",")) {
                String playerName = extractPartyUsername(rawMember);
                if (!playerName.isBlank()) trackPartyMember(playerName, now);
            }
            return;
        }

        // Somebody joined a party we were already in.
        int joinedIndex = lower.indexOf(" joined the party.");
        if (joinedIndex >= 0) {
            String playerName = extractPartyUsername(text.substring(0, joinedIndex));
            if (!playerName.isBlank()) trackPartyMember(playerName, now);
            return;
        }

        // Party chat is also a strong membership signal and recovers if the
        // initial roster announcement was missed because of a whatever idk
        Matcher partyChat = PARTY_CHAT_SENDER.matcher(text);
        if (partyChat.find()) {
            trackPartyMember(partyChat.group(1), now);
            return;
        }

        // A later party should count as a new encounter, so forget the current roster when
        // the local player leaves or the party ceases to exist.
        if (lower.equals("you have left the party.")
                || lower.equals("you have left the party")
                || lower.contains("you have been kicked from the party")
                || lower.contains("you were kicked from the party")
                || lower.contains("the party was disbanded")
                || lower.contains("the party has been disbanded")
                || lower.contains("you are not currently in a party")) {
            currentPartyMembers.clear();
            return;
        }

        // If another member leaves, allow a later rejoin to count as another encounter.
        Matcher memberLeft = PARTY_MEMBER_LEFT.matcher(text);
        if (memberLeft.matches()) {
            currentPartyMembers.remove(memberLeft.group(1).toLowerCase(Locale.ROOT));
        }
    }

    private static void trackPartyMember(String playerName, long now) {
        if (playerName == null || playerName.isBlank()) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null && playerName.equalsIgnoreCase(minecraft.player.getName().getString())) return;

        String key = playerName.toLowerCase(Locale.ROOT);
        if (!currentPartyMembers.add(key)) {
            // Refresh recency, but do not turn every party-chat message into a new encounter.
            PLAYER_MEMORY.observePlayer(playerName, now);
            return;
        }

        PreviousEncounter previous = PLAYER_MEMORY.recordPartyJoin(playerName, now);
        DayStats day = WRAPPED.today();
        day.encounteredPlayers.merge(playerName, 1, Integer::sum);

        if (previous.known()) {
            long days = previous.daysSince(now);
            if (days >= 30) {
                day.reunions.merge(playerName, 1, Integer::sum);
                day.reunionGapDays.merge(playerName, (int) Math.min(Integer.MAX_VALUE, days), Math::max);
            }
            showPlayerMemoryLine(playerName, previous, days);
        }
        WRAPPED.markDirty();
    }

    private static String extractPartyUsername(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String cleaned = raw
                .replaceAll("\\[[^]]+]", "")
                .replace("●", " ")
                .replace("○", " ")
                .replaceAll("[.!]+$", "")
                .trim();
        Matcher matcher = USERNAME_AT_END.matcher(cleaned);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static void showPlayerMemoryLine(String playerName, PreviousEncounter previous, long days) {
        Minecraft minecraft = Minecraft.getInstance();
        StringBuilder line = new StringBuilder("§8↻ §b").append(playerName).append(" §7- ");
        if (previous.dungeonRuns() > 0) line.append(previous.dungeonRuns()).append(" runs together");
        else line.append(previous.encounters()).append(" previous encounters");
        if (!previous.usualClass().equalsIgnoreCase("Unknown")) line.append(" §8| §7usually ").append(previous.usualClass());
        if (days > 0) line.append(" §8| §7last seen ").append(days).append(days == 1 ? " day ago" : " days ago");

        minecraft.execute(() -> minecraft.gui.getChat().addClientSystemMessage(Component.literal(line.toString())));
    }

    private static void parsePet(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("despawned your pet") || lower.contains("you despawned")) {
            currentPet = "";
            return;
        }

        // Remove autopet messages
        String normalized = text.replaceFirst("(?i)\\s*!?\\s*VIEW RULE\\s*$", "").trim();
        Matcher matcher = PET.matcher(normalized);
        if (matcher.find()) currentPet = sanitizePetName(matcher.group(1));
    }

    private static void parseFinancials(String text) {
        Matcher amountMatcher = COINS.matcher(text);
        if (!amountMatcher.find()) return;
        long amount = parseCompactNumber(amountMatcher.group(1), amountMatcher.group(2));
        if (amount <= 0) return;

        String lower = text.toLowerCase(Locale.ROOT);
        String body = lower.replaceFirst("^\\[[^]]+]\\s*", "").trim();
        long now = System.currentTimeMillis();

        // These move the purse without representing income/spending. We ignore those
        if (body.contains("deposited") && body.contains("coins")) {
            ignorePurseDelta(amount, -1, now);
            return;
        }
        if ((body.contains("withdrew") || body.contains("withdrawn")) && body.contains("coins")) {
            ignorePurseDelta(amount, 1, now);
            return;
        }
        if ((body.contains("lost") || body.contains("died")) && body.contains("coins")) {
            ignorePurseDelta(amount, -1, now);
            return;
        }
        if (body.contains("refunded") && body.contains("coins")) {
            ignorePurseDelta(amount, 1, now);
            return;
        }

        DayStats day = WRAPPED.today();
        boolean earned = body.startsWith("you sold")
                || body.startsWith("sold ")
                || body.startsWith("you earned")
                || body.startsWith("you collected") || body.contains("from selling")
                || body.startsWith("claiming ")
                || body.startsWith("coins from selling")
                || body.startsWith("sold for");
        boolean tax = !earned && (body.contains("tax") || body.contains("fee"));
        boolean spent = body.startsWith("you bought")
                || body.startsWith("bought ")
                || body.startsWith("you purchased")
                || body.startsWith("you paid")
                || body.startsWith("purchase cost")
                || tax;

        if (!earned && !spent) return;

        if (!purseTrackingSeen) {
            if (earned) day.coinsEarned += amount;
            else day.coinsSpent += amount;
        }

        if (earned) {
            if (amount > day.biggestSale) {
                day.biggestSale = amount;
                day.biggestSaleItem = transactionSubject(text);
            }
        } else {
            if (tax) day.taxesPaid += amount;
            if (!tax && amount > day.biggestPurchase) {
                day.biggestPurchase = amount;
                day.biggestPurchaseItem = transactionSubject(text);
            }
        }

        WRAPPED.markDirty();
    }

    private static void observePurse(long purse, long now) {
        if (purse < 0) return;
        purseTrackingSeen = true;

        if (lastObservedPurse < 0) {
            lastObservedPurse = purse;
            return;
        }

        long delta = purse - lastObservedPurse;
        lastObservedPurse = purse;
        if (delta == 0) return;

        int direction = delta > 0 ? 1 : -1;
        long amount = Math.abs(delta);
        boolean ignored = now < pendingIgnoredPurseUntilMillis
                && pendingIgnoredPurseDirection == direction
                && amountsMatch(pendingIgnoredPurseAmount, amount);

        if (ignored) {
            clearPendingPurseIgnore();
            rememberPurseDelta(amount, direction, now, false);
            return;
        }

        DayStats day = WRAPPED.today();
        if (direction > 0) day.coinsEarned += amount;
        else day.coinsSpent += amount;
        rememberPurseDelta(amount, direction, now, true);
        WRAPPED.markDirty();
    }

    private static void ignorePurseDelta(long amount, int direction, long now) {
        if (lastPurseDeltaCounted
                && now - lastPurseDeltaMillis <= PURSE_IGNORE_WINDOW_MILLIS
                && lastPurseDeltaDirection == direction
                && amountsMatch(lastPurseDeltaAmount, amount)) {
            DayStats day = WRAPPED.today();
            if (direction > 0) day.coinsEarned = Math.max(0L, day.coinsEarned - lastPurseDeltaAmount);
            else day.coinsSpent = Math.max(0L, day.coinsSpent - lastPurseDeltaAmount);
            lastPurseDeltaCounted = false;
            WRAPPED.markDirty();
            return;
        }

        pendingIgnoredPurseAmount = amount;
        pendingIgnoredPurseDirection = direction;
        pendingIgnoredPurseUntilMillis = now + PURSE_IGNORE_WINDOW_MILLIS;
    }

    private static boolean amountsMatch(long expected, long actual) {
        return expected == actual || Math.abs(expected - actual) <= 1L;
    }

    private static void rememberPurseDelta(long amount, int direction, long now, boolean counted) {
        lastPurseDeltaAmount = amount;
        lastPurseDeltaDirection = direction;
        lastPurseDeltaMillis = now;
        lastPurseDeltaCounted = counted;
    }

    private static void clearPendingPurseIgnore() {
        pendingIgnoredPurseAmount = 0L;
        pendingIgnoredPurseDirection = 0;
        pendingIgnoredPurseUntilMillis = 0L;
    }

    private static void parseDungeon(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (!lower.contains("catacombs") && !lower.contains("master mode")) return;
        Matcher floorMatcher = DUNGEON_FLOOR.matcher(text);
        if (floorMatcher.find()) {
            String token = floorMatcher.group(1) != null ? floorMatcher.group(1) : floorMatcher.group(2);
            dungeonFloor = normalizeFloor(token, text);
        }
    }

    private static void parseSlayer(String text, long now) {
        String lower = text.toLowerCase(Locale.ROOT);
        String detected = detectSlayer(lower);
        if (detected != null) currentSlayer = detected;

        if (text.equalsIgnoreCase("SLAYER QUEST COMPLETE!")) {
            DayStats day = WRAPPED.today();
            String slayer = currentSlayer.isBlank() ? "Unknown" : currentSlayer;
            day.slayerBosses++;
            day.slayerBossesByType.merge(slayer, 1, Integer::sum);
            if (slayerBossStartMillis > 0) {
                long duration = Math.max(1L, now - slayerBossStartMillis);
                day.fastestSlayerMillis.merge(slayer, duration, SkyblockHistoryFeature::minPositive);
            }
            lastCompletedSlayer = slayer;
            lastSlayerCompleteMillis = now;
            currentSlayer = "";
            slayerBossStartMillis = 0L;
            WRAPPED.markDirty();
            return;
        }

        boolean recentSlayer = !currentSlayer.isBlank()
                || (!lastCompletedSlayer.isBlank() && now - lastSlayerCompleteMillis <= 5_000);
        if (recentSlayer && (lower.contains("pray to rngesus drop") || lower.contains("insane drop") || lower.contains("crazy rare drop"))) {
            WRAPPED.today().slayerRngDrops++;
            WRAPPED.markDirty();
        }
    }

    private static void parseActivityMessages(String text, long now) {
        String lower = text.toLowerCase(Locale.ROOT);

        if (lower.contains("commission complete")) {
            WRAPPED.today().commissionsCompleted++;
            WRAPPED.markDirty();
        }

        if (text.equalsIgnoreCase("[NPC] Jacob: The Farming Contest is over!")) {
            String instance = "jacob:day:" + skyblockAbsoluteDay(now);
            if (recordEventInstance("Jacob's Farming Contest", instance)) {
                WRAPPED.today().jacobsContests++;
                WRAPPED.markDirty();
            }
        }

        if (text.startsWith("You dug out a Griffin Burrow!") || text.startsWith("You finished the Griffin burrow chain!")) {
            recordEventInstance("Mythological Ritual", "mythological:year:" + skyblockYear(now));
        }

        if (text.startsWith("HOPPITY'S HUNT You found ") && lower.contains("egg")) {
            recordEventInstance("Hoppity's Hunt", "hoppity:year:" + skyblockYear(now));
        }
    }

    private static void sampleTabSignals(Minecraft minecraft, LocationUtils.Island area) {
        if (minecraft.getConnection() == null) return;

        int observedPests = -1;
        boolean spookyParticipation = false;
        boolean jerryWave = false;
        boolean bingoProfile = false;
        String observedKuudraTier = "";

        for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) continue;
            String line = clean(display.getString());
            String lower = line.toLowerCase(Locale.ROOT);

            if (area == LocationUtils.Island.KUUDRA && observedKuudraTier.isBlank()) {
                observedKuudraTier = kuudraTierFromText(lower);
            }

            if (area == LocationUtils.Island.GARDEN) {
                Matcher pestMatcher = Pattern.compile("(?i)\\bpests?\\s*:\\s*([0-9][0-9,]*)\\b").matcher(line);
                if (pestMatcher.find()) observedPests = (int) parseLong(pestMatcher.group(1));
            }

            if (lower.contains("spooky") && (lower.contains("point") || lower.contains("score"))) {
                Matcher number = Pattern.compile("([0-9][0-9,]*)").matcher(line);
                if (number.find() && parseLong(number.group(1)) > 0) spookyParticipation = true;
            }

            if (area == LocationUtils.Island.JERRY_WORKSHOP && (lower.contains("next wave") || lower.matches(".*\\bwave\\s+\\d+.*"))) {
                jerryWave = true;
            }

            if (lower.contains("profile: bingo") || lower.contains("bingo profile")) bingoProfile = true;
        }

        if (area == LocationUtils.Island.GARDEN && observedPests >= 0) {
            if (lastObservedPestCount >= 0 && observedPests < lastObservedPestCount) {
                WRAPPED.today().pestsKilled += lastObservedPestCount - observedPests;
                WRAPPED.markDirty();
            }
            lastObservedPestCount = observedPests;
        }

        if (!observedKuudraTier.isBlank()) currentKuudraTier = observedKuudraTier;

        long now = System.currentTimeMillis();
        if (spookyParticipation) recordEventInstance("Spooky Festival", "spooky:year:" + skyblockYear(now));
        if (jerryWave) recordEventInstance("Season of Jerry", "jerry:year:" + skyblockYear(now));
        if (bingoProfile) {
            YearMonth month = YearMonth.from(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC));
            recordEventInstance("Bingo", "bingo:" + month);
        }
    }

    private static void parseKuudraTierHint(String text) {
        if (text == null || text.isBlank()) return;
        String lower = clean(text).toLowerCase(Locale.ROOT);
        if (!lower.contains("kuudra") && LocationUtils.getCurrentArea() != LocationUtils.Island.KUUDRA) return;
        String tier = kuudraTierFromText(lower);
        if (!tier.isBlank()) currentKuudraTier = tier;
    }

    private static String kuudraTierFromText(String text) {
        if (text == null || text.isBlank()) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        boolean hasContext = lower.contains("kuudra") || lower.contains("tier") || lower.matches(".*\\bt[1-5]\\b.*");
        if (!hasContext) return "";
        if (lower.contains("infernal") || lower.matches(".*\\\\bt5\\\\b.*")) return "Infernal";
        if (lower.contains("fiery") || lower.matches(".*\\bt4\\b.*")) return "Fiery";
        if (lower.contains("burning") || lower.matches(".*\\bt3\\b.*")) return "Burning";
        if (lower.contains("hot") || lower.matches(".*\\bt2\\b.*")) return "Hot";
        if (lower.contains("basic") || lower.matches(".*\\bt1\\b.*")) return "Basic";
        return "";
    }

    private static void parseRiftMotes(String text) {
        if (LocationUtils.getCurrentArea() != LocationUtils.Island.RIFT) return;
        Matcher matcher = MOTES.matcher(text);
        if (!matcher.find()) return;
        long observed = parseLong(matcher.group(1));
        if (observed < 0) return;

        if (currentMotes >= 0 && observed != currentMotes) {
            long delta = observed - currentMotes;
            if (delta > 0) WRAPPED.today().riftMotesEarned += delta;
            else WRAPPED.today().riftMotesSpent += -delta;
            WRAPPED.markDirty();
        }
        currentMotes = observed;
    }

    private static void parseDeath(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        String name = minecraft.player.getName().getString();
        String lower = text.toLowerCase(Locale.ROOT);
        String lowerName = name.toLowerCase(Locale.ROOT);
        if (!lower.contains(lowerName)) return;
        boolean deathMessage = text.contains("☠")
                || lower.contains(lowerName + " died")
                || lower.contains(lowerName + " was slain")
                || lower.contains(lowerName + " was killed")
                || lower.contains(lowerName + " fell")
                || lower.contains(lowerName + " burned")
                || lower.contains(lowerName + " drowned");
        if (!deathMessage) return;

        DayStats day = WRAPPED.today();
        LocationUtils.Island area = LocationUtils.getCurrentArea();
        if (area == LocationUtils.Island.DUNGEON || dungeonStartMillis > 0) day.dungeonDeaths++;
        if (!currentSlayer.isBlank()) day.slayerDeaths++;
        if (area == LocationUtils.Island.RIFT) day.riftDeathsByCause.merge(deathCause(text), 1, Integer::sum);
        if (heldLooksLikeFishingRod(minecraft.player.getMainHandItem())) day.fishingDeaths++;
        WRAPPED.markDirty();
    }

    private static void finishKuudra(boolean failed) {
        if (kuudraStartMillis <= 0) return;
        long seconds = Math.max(1L, (System.currentTimeMillis() - kuudraStartMillis) / 1000L);
        DayStats day = WRAPPED.today();
        day.kuudraRuns++;
        if (failed) day.kuudraFailedRuns++;
        if (!currentKuudraTier.isBlank()) day.kuudraTierRuns.merge(currentKuudraTier, 1, Integer::sum);
        day.fastestKuudraSeconds = minPositive(day.fastestKuudraSeconds, seconds);
        day.slowestKuudraSeconds = Math.max(day.slowestKuudraSeconds, seconds);
        kuudraStartMillis = 0L;
        currentKuudraTier = "";
        WRAPPED.markDirty();
    }
    
    private static boolean recordEventInstance(String event, String instanceId) {
        String runtimeKey = event + "|" + instanceId;
        if (!runtimeEventInstances.add(runtimeKey)) return false;
        return WRAPPED.recordEventInstance(event, instanceId);
    }

    private static long skyblockYear(long now) {
        long elapsed = Math.max(0L, now - SKYBLOCK_EPOCH_MILLIS);
        return Math.floorDiv(elapsed, SKYBLOCK_YEAR_MILLIS) + 1L;
    }

    private static long skyblockAbsoluteDay(long now) {
        long elapsed = Math.max(0L, now - SKYBLOCK_EPOCH_MILLIS);
        return Math.floorDiv(elapsed, SKYBLOCK_DAY_MILLIS);
    }

    private static String darkAuctionInstanceId(long now) {
        // Dark Auction opens at :55.
        long shiftedHour = Math.floorDiv(now + 5L * 60_000L, 60L * 60_000L);
        return "dark-auction:hour:" + shiftedHour;
    }

    private static void sampleFishing(WrappedStore.DayStats day, String heldName, LocationUtils.Island area) {
        if (heldName.isBlank() || !heldName.toLowerCase(Locale.ROOT).contains("rod")) return;
        if (area == LocationUtils.Island.CRIMSON_ISLE) day.lavaFishingSeconds++;
        else day.waterFishingSeconds++;
    }

    private static String detectPetFromTab(Minecraft minecraft) {
        if (minecraft.getConnection() == null) return "";
        for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
            Component display = info.getTabListDisplayName();
            if (display == null) continue;
            String line = clean(display.getString());
            String lower = line.toLowerCase(Locale.ROOT);
            int index = lower.indexOf("pet:");
            if (index < 0) continue;
            String pet = sanitizePetName(line.substring(index + 4));
            if (!pet.isBlank() && !pet.equalsIgnoreCase("none")) return pet;
        }
        return "";
    }

    private static String sanitizePetName(String raw) {
        if (raw == null) return "";
        return raw
                .replaceFirst("(?i)\\s*!?\\s*VIEW RULE\\s*$", "")
                .replaceAll("\\[[^]]+]", "")
                .replaceAll("[!.]+$", "")
                .trim();
    }

    private static String armorSetName(Player player) {
        List<String> pieces = new ArrayList<>(4);
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            String name = itemName(player.getItemBySlot(slot));
            if (!name.isBlank()) pieces.add(name);
        }
        if (pieces.isEmpty()) return "";

        List<String> bases = pieces.stream().map(SkyblockHistoryFeature::stripArmorPiece).toList();
        String first = bases.getFirst();
        if (pieces.size() >= 3 && bases.stream().allMatch(first::equalsIgnoreCase)) return first;
        return String.join(" + ", pieces);
    }

    private static String itemName(ItemStack stack) {
        SkyblockItemResolver.ItemIdentity identity = SkyblockItemResolver.resolveIdentity(stack);
        String name = identity.displayNameWithoutModifier();
        if (!name.isBlank()) return name;
        return identity.internalId().replace('_', ' ');
    }

    private static String stripArmorPiece(String name) {
        return name.replaceFirst("(?i)\\s+(helmet|chestplate|tunic|leggings|pants|boots)$", "").trim();
    }

    private static boolean heldLooksLikeFishingRod(ItemStack stack) {
        String name = itemName(stack).toLowerCase(Locale.ROOT);
        if (name.contains("rod")) return true;
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains("fishing_rod");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String cropName(String block, Minecraft minecraft) {
        if (!CROP_BLOCKS.contains(block)) return null;
        return switch (block) {
            case "carrots" -> "Carrot";
            case "potatoes" -> "Potato";
            case "nether_wart" -> "Nether Wart";
            case "cocoa" -> "Cocoa Beans";
            case "sugar_cane" -> "Sugar Cane";
            case "brown_mushroom", "red_mushroom" -> "Mushroom";
            case "rose_bush" -> "Wild Rose";
            case "sunflower" -> isGardenNight(minecraft) ? "Moonflower" : "Sunflower";
            default -> title(block);
        };
    }

    private static boolean isGardenNight(Minecraft minecraft) {
        if (minecraft == null || minecraft.level == null) return false;
        long time = Math.floorMod(minecraft.level.getOverworldClockTime(), 24_000L);
        return time >= 13_000L || time < 1_000L;
    }

    private static String woodName(String block, LocationUtils.Island area) {
        if (block == null || block.isBlank()) return null;
        String normalized = block.toLowerCase(Locale.ROOT);

        if (area == LocationUtils.Island.GALATEA) {
            if (isAnyWood(normalized, "spruce") || isAnyWood(normalized, "oak")) return "Fig";
            if (isAnyWood(normalized, "mangrove")) return "Mangrove";
            return null;
        }

        if (area == LocationUtils.Island.THORRUS_CANYON) {
            if (isAnyWood(normalized, "birch") || isAnyWood(normalized, "mangrove")) return "Helix";
            return null;
        }

        if (normalized.startsWith("stripped_")) normalized = normalized.substring("stripped_".length());
        if (normalized.endsWith("_log")) normalized = normalized.substring(0, normalized.length() - 4);
        else if (normalized.endsWith("_wood")) normalized = normalized.substring(0, normalized.length() - 5);
        else if (normalized.endsWith("_stem")) normalized = normalized.substring(0, normalized.length() - 5);
        else if (normalized.endsWith("_hyphae")) normalized = normalized.substring(0, normalized.length() - 8);
        else return null;
        return title(normalized);
    }

    private static boolean isAnyWood(String block, String type) {
        String value = block.startsWith("stripped_") ? block.substring("stripped_".length()) : block;
        return value.equals(type + "_log") || value.equals(type + "_wood")
                || value.equals(type + "_stem") || value.equals(type + "_hyphae");
    }

    private static String miningResourceName(String block) {
        String value = block.replace("deepslate_", "");
        if (value.endsWith("_ore")) return title(value.substring(0, value.length() - 4));
        return switch (value) {
            case "coal_block" -> "Coal";
            case "iron_block" -> "Iron";
            case "gold_block" -> "Gold";
            case "diamond_block" -> "Diamond";
            case "emerald_block" -> "Emerald";
            case "redstone_block" -> "Redstone";
            case "lapis_block" -> "Lapis";
            default -> null;
        };
    }

    private static String detectSlayer(String lower) {
        if (lower.contains("revenant horror") || lower.contains("zombie slayer")) return "Zombie";
        if (lower.contains("tarantula broodfather") || lower.contains("spider slayer")) return "Spider";
        if (lower.contains("sven packmaster") || lower.contains("wolf slayer")) return "Wolf";
        if (lower.contains("voidgloom seraph") || lower.contains("enderman slayer")) return "Enderman";
        if (lower.contains("inferno demonlord") || lower.contains("blaze slayer")) return "Blaze";
        if (lower.contains("riftstalker bloodfiend") || lower.contains("vampire slayer")) return "Vampire";
        return null;
    }

    private static String deathCause(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int by = lower.lastIndexOf(" by ");
        if (by >= 0 && by + 4 < text.length()) return text.substring(by + 4).replaceAll("[.!]+$", "").trim();
        if (lower.contains("void")) return "Void";
        if (lower.contains("fell")) return "Fall";
        return "Unknown";
    }

    private static String transactionSubject(String text) {
        String cleaned = text.replaceFirst("(?i)^\\[[^]]+]\\s*", "")
                .replaceFirst("(?i)^(you\\s+)?(bought|purchased|sold|claiming)\\s+", "");
        int forIndex = cleaned.toLowerCase(Locale.ROOT).lastIndexOf(" for ");
        if (forIndex > 0) cleaned = cleaned.substring(0, forIndex);
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned.trim();
    }

    private static long parseCompactNumber(String number, String suffix) {
        try {
            double value = Double.parseDouble(number.replace(",", ""));
            if (suffix != null) {
                value *= switch (suffix.toLowerCase(Locale.ROOT)) {
                    case "k" -> 1_000D;
                    case "m" -> 1_000_000D;
                    case "b" -> 1_000_000_000D;
                    case "t" -> 1_000_000_000_000D;
                    default -> 1D;
                };
            }
            return Math.max(0L, Math.round(value));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static String normalizeFloor(String token, String fullText) {
        String upper = token.toUpperCase(Locale.ROOT);
        if (upper.matches("[FM]\\d+")) return upper;
        String floor = ROMAN_FLOORS.getOrDefault(upper, upper);
        if (fullText.toLowerCase(Locale.ROOT).contains("master")) return floor.replace('F', 'M');
        return floor;
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("(?i)§[0-9A-FK-ORX]", "").replaceAll("\\s+", " ").trim();
    }

    private static String title(String raw) {
        StringBuilder result = new StringBuilder();
        for (String word : raw.split("_")) {
            if (word.isBlank()) continue;
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private static long minPositive(long left, long right) {
        if (left <= 0) return right;
        if (right <= 0) return left;
        return Math.min(left, right);
    }

    private static boolean partyMemoryEnabled() {
        return NsmConfig.INSTANCE.other.partyMemory.enabled;
    }

    private static boolean tracking() {
        return LocationUtils.isInSkyblock();
    }

    private static void onDisconnect() {
        if (sessionActive) {
            sessionActive = false;
            sessionSeconds = 0L;
        }
        if (dungeonStartMillis > 0) onDungeonRunEnd(true);
        kuudraStartMillis = 0L;
        currentKuudraTier = "";
        currentPet = "";
        currentSlayer = "";
        slayerBossStartMillis = 0L;
        currentMotes = -1L;
        lastObservedPestCount = -1;
        lastObservedPurse = -1L;
        purseTrackingSeen = false;
        clearPendingPurseIgnore();
        lastPurseDeltaAmount = 0L;
        lastPurseDeltaDirection = 0;
        lastPurseDeltaMillis = 0L;
        lastPurseDeltaCounted = false;
        lastCompletedSlayer = "";
        lastSlayerCompleteMillis = 0L;
        previousArea = LocationUtils.Island.UNKNOWN;
        currentPartyMembers.clear();
        WRAPPED.save();
        PLAYER_MEMORY.save();
    }
}

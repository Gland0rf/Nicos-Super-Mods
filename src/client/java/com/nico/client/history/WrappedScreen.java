package com.nico.client.history;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WrappedScreen extends Screen {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy");
    private static final long AUTO_ADVANCE_MS = 9_000L;
    private static final long ENTER_ANIMATION_MS = 520L;
    private static final int FOOTER_HEIGHT = 96;

    static final int[][] PALETTES = {
            {0xFF4D00FF, 0xFFFF3D81},
            {0xFF007A5A, 0xFF85E85A},
            {0xFF002F6C, 0xFF17B7FF},
            {0xFF7A1700, 0xFFFFB000},
            {0xFF32115F, 0xFFD35CFF},
            {0xFF00485C, 0xFF25E4C4},
            {0xFF6B003A, 0xFFFF6188},
            {0xFF3B4D00, 0xFFB8EA32}
    };

    private final Screen parent;
    private final YearMonth month;
    private final WrappedStore.WrappedSnapshot snapshot;
    private final WrappedConfig.Settings settings;
    private final List<Slide> slides;

    private int slideIndex;
    private long slideStartedAt;
    private boolean paused;
    private long pausedAt;
    private String exportStatus = "";

    public WrappedScreen(Screen parent, YearMonth month) {
        super(Component.literal("SkyBlock Wrapped"));
        this.parent = parent;
        this.month = month;
        this.snapshot = SkyblockHistoryFeature.snapshot(month);
        this.settings = WrappedConfig.load();
        this.slides = buildSlides();
        this.slideStartedAt = System.currentTimeMillis();
    }

    @Override
    protected void init() {
        slideIndex = Math.max(0, Math.min(slideIndex, slides.size() - 1));
        slideStartedAt = System.currentTimeMillis();

        int buttonWidth = 116;
        int buttonGap = 8;
        int buttonY = height - 28;
        int left = width / 2 - buttonWidth - buttonGap / 2;

        addRenderableWidget(
                Button.builder(Component.literal("Export as PDF"), button -> exportPdf())
                        .bounds(left, buttonY, buttonWidth, 20)
                        .build()
        );

        addRenderableWidget(
                Button.builder(Component.literal("Open Export Folder"), button -> openExportFolder())
                        .bounds(left + buttonWidth + buttonGap, buttonY, buttonWidth, 20)
                        .build()
        );
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        if (slides.isEmpty()) {
            graphics.fill(0, 0, width, height, 0xFF11131A);
            graphics.centeredText(font, "Wrapped has nothing to show yet.", width / 2, height / 2, 0xFFFFFFFF);
            super.extractRenderState(graphics, mouseX, mouseY, a);
            return;
        }

        long now = System.currentTimeMillis();
        maybeAutoAdvance(now);

        Slide slide = slides.get(slideIndex);
        renderAnimatedBackground(graphics, slide, now);
        renderProgress(graphics, now);
        renderSlide(graphics, slide, now);
        renderNavigationHint(graphics);
        renderExportFolder(graphics);

        super.extractRenderState(graphics, mouseX, mouseY, a);
    }

    private void renderAnimatedBackground(GuiGraphicsExtractor graphics, Slide slide, long now) {
        int[] palette = PALETTES[Math.floorMod(slide.palette(), PALETTES.length)];
        double drift = (Math.sin(now / 1700.0 + slideIndex * 0.9) + 1.0) * 0.5;
        int top = mix(palette[0], palette[1], 0.12 + 0.13 * drift);
        int bottom = mix(palette[0], palette[1], 0.76 - 0.11 * drift);

        int strips = Math.max(18, Math.min(48, height / 8));
        for (int i = 0; i < strips; i++) {
            double t = strips <= 1 ? 0.0 : (double) i / (strips - 1);
            int y1 = i * height / strips;
            int y2 = (i + 1) * height / strips + 1;
            graphics.fill(0, y1, width, y2, mix(top, bottom, t));
        }

        // Slow-moving translucent geometry accents. Should be cheap enough to render each frame.
        for (int i = 0; i < 9; i++) {
            double phase = now / (2300.0 + i * 170.0) + i * 1.37 + slideIndex;
            int w = 26 + (i % 4) * 18;
            int h = 7 + (i % 3) * 7;
            int x = (int) ((Math.sin(phase) * 0.5 + 0.5) * (width + 100)) - 50;
            int y = (int) ((Math.cos(phase * 0.73) * 0.5 + 0.5) * (height + 60)) - 30;
            int color = withAlpha(i % 2 == 0 ? palette[0] : palette[1], 38 + (i % 3) * 12);
            graphics.fill(x, y, x + w, y + h, color);
        }

        // Vignette-ish top/bottom bands for readability
        graphics.fill(0, 0, width, 32, 0x44000000);
        graphics.fill(0, height - 78, width, height, 0x55000000);
    }

    private void renderProgress(GuiGraphicsExtractor graphics, long now) {
        int count = slides.size();
        int gap = 3;
        int left = 12;
        int right = width - 12;
        int available = Math.max(1, right - left - gap * (count - 1));
        int segmentWidth = Math.max(2, available / Math.max(1, count));
        double currentProgress;
        if (slideIndex == slides.size() - 1) {
            currentProgress = 1.0;
        } else {
            long progressNow = paused && pausedAt > 0 ? pausedAt : now;
            currentProgress = clamp01((double) (progressNow - slideStartedAt) / AUTO_ADVANCE_MS);
        }

        int x = left;
        for (int i = 0; i < count; i++) {
            int actualRight = i == count - 1 ? right : x + segmentWidth;
            graphics.fill(x, 8, actualRight, 11, 0x55FFFFFF);
            if (i < slideIndex) {
                graphics.fill(x, 8, actualRight, 11, 0xFFFFFFFF);
            } else if (i == slideIndex) {
                int filled = x + (int) ((actualRight - x) * currentProgress);
                graphics.fill(x, 8, filled, 11, 0xFFFFFFFF);
            }
            x = actualRight + gap;
        }
    }

    private void renderSlide(GuiGraphicsExtractor graphics, Slide slide, long now) {
        double enter = clamp01((double) (now - slideStartedAt) / ENTER_ANIMATION_MS);
        double eased = 1.0 - Math.pow(1.0 - enter, 3.0);
        int offsetY = (int) ((1.0 - eased) * 22.0);
        int alpha = (int) (255 * Math.min(1.0, enter * 1.35));

        int contentWidth = Math.min(610, Math.max(280, width - 44));
        int left = (width - contentWidth) / 2;
        int top = (slide.kind() == SlideKind.TIME ? Math.max(28, height / 2 - 190) : Math.max(42, height / 2 - 142)) + offsetY;

        renderScaledCentered(graphics, slide.kicker().toUpperCase(Locale.ROOT), width / 2, top, 0.95f, withAlpha(0xFFFFFFFF, Math.max(80, alpha * 3 / 4)), true);
        renderScaledCentered(graphics, slide.title(), width / 2, top + 23, titleScale(slide.title()), withAlpha(0xFFFFFFFF, Math.max(80, alpha * 3 / 4)), true);

        if (!slide.subtitle().isBlank()) {
            graphics.centeredText(font, slide.subtitle(), width / 2, top + 55, withAlpha(0xFFFFFFFF, Math.max(90, alpha * 4 / 5)));
        }

        if (slide.kind() == SlideKind.INTRO) {
            renderIntro(graphics, slide, top + 86, alpha);
            return;
        }

        int statTop = top + 78;
        if (slide.kind() == SlideKind.TIME) {
            statTop = renderTimeStats(graphics, slide.stats(), left, statTop, contentWidth, alpha);
            renderHeatmap(graphics, statTop + 10, contentWidth, alpha);
        } else {
            renderStatGrid(graphics, slide.stats(), left, statTop, contentWidth, alpha, 8);
        }
    }

    private void renderIntro(GuiGraphicsExtractor graphics, Slide slide, int y, int alpha) {
        String range = snapshot.start.format(DATE) + "  -  " + snapshot.end.format(DATE);
        graphics.centeredText(font, range, width / 2, y, withAlpha(0xFFDADADA, alpha));
        graphics.centeredText(font, month.format(MONTH) + " in SkyBlock", width / 2, y + 18, withAlpha(0xFFEFEFEF, alpha));

        if (snapshot.playtimeSeconds > 0) {
            renderScaledCentered(graphics, formatDuration(snapshot.playtimeSeconds), width / 2, y + 48, 1.65f, withAlpha(0xFFFFFFFF, alpha), true);
            graphics.centeredText(font, "tracked playtime", width / 2, y + 68, withAlpha(0xFFEFEFEF, alpha));
        }
    }

    private int renderStatGrid(GuiGraphicsExtractor graphics, List<Stat> stats, int left, int top, int contentWidth, int alpha, int maxStats) {
        int shown = Math.min(maxStats, stats.size());
        if (shown <= 0) {
            graphics.centeredText(font, "Nothing meaningful was recorded for this card.", width / 2, top + 22, withAlpha(0xFFEFEFEF, alpha));
            return top + 48;
        }

        int columns = contentWidth >= 430 ? 2 : 1;
        int gap = 8;
        int cardWidth = (contentWidth - gap * (columns - 1)) / columns;
        int cardHeight = 42;
        int rows = (shown + columns - 1) / columns;

        for (int i = 0; i < shown; i++) {
            Stat stat = stats.get(i);
            int column = i % columns;
            int row = i / columns;
            int x = left + column * (cardWidth + gap);
            int y = top + row * (cardHeight + gap);

            graphics.fill(x, y, x + cardWidth, y + cardHeight, withAlpha(0xFF05050A, Math.max(55, alpha / 2)));
            graphics.fill(x, y, x + 3, y + cardHeight, withAlpha(0xFFFFFFFF, Math.max(80, alpha * 3 / 4)));

            String value = font.plainSubstrByWidth(stat.value(), Math.max(20, cardWidth - 18));
            String label = font.plainSubstrByWidth(stat.label(), Math.max(20, cardWidth - 18));
            graphics.text(font, value, x + 10, y + 8, withAlpha(0xFFFFFFFF, alpha), true);
            graphics.text(font, label, x + 10, y + 24, withAlpha(0xFFDADADA, Math.max(75, alpha * 4 / 5)), false);
        }

        return top + rows * (cardHeight + gap);
    }

    private int renderTimeStats(
            GuiGraphicsExtractor graphics,
            List<Stat> stats,
            int left,
            int top,
            int contentWidth,
            int alpha
    ) {
        int shown = Math.min(4, stats.size());
        if (shown <= 0) return top;

        int columns = contentWidth >= 420 ? shown : contentWidth >= 340 ? Math.min(2, shown) : 1;
        int gap = 8;
        int cardWidth = (contentWidth - gap * (columns - 1)) / columns;
        int cardHeight = 34;
        int rows = (shown + columns - 1) / columns;

        for (int i = 0; i < shown; i++) {
            Stat stat = stats.get(i);
            int column = i % columns;
            int row = i / columns;
            int x = left + column * (cardWidth + gap);
            int y = top + row * (cardHeight + gap);

            graphics.fill(x, y, x + cardWidth, y + cardHeight, withAlpha(0xFF05050A, Math.max(55, alpha / 2)));
            graphics.fill(x, y, x + 3, y + cardHeight, withAlpha(0xFFFFFFFF, Math.max(80, alpha * 3 / 4)));

            String value = font.plainSubstrByWidth(stat.value(), Math.max(20, cardWidth - 18));
            String label = font.plainSubstrByWidth(stat.label(), Math.max(20, cardWidth - 18));
            graphics.text(font, value, x + 10, y + 5, withAlpha(0xFFFFFFFF, alpha), true);
            graphics.text(font, label, x + 10, y + 19, withAlpha(0xFFDADADA, Math.max(75, alpha * 4 / 5)), false);
        }

        return top + rows * (cardHeight + gap);
    }

    private void renderHeatmap(GuiGraphicsExtractor graphics, int top, int contentWidth, int alpha) {
        if (!settings.time.daysPlayedHeatmap || snapshot.playtimeByDay.isEmpty()) return;

        LocalDate end = snapshot.end;
        LocalDate start = snapshot.start.isAfter(end.minusDays(364)) ? snapshot.start : end.minusDays(364);
        LocalDate gridStart = start.minusDays(start.getDayOfWeek().getValue() - 1L);
        LocalDate gridEnd = end.plusDays(7L - end.getDayOfWeek().getValue());
        int weeks = (int) Math.max(1L, ChronoUnit.WEEKS.between(gridStart, gridEnd.plusDays(1)));
        int gap = 2;
        int labelGap = 6;
        int labelWidth = Math.max(font.width("Mon"), Math.max(font.width("Wed"), font.width("Fri")));

        int gridTop = top + 32;
        // All slide content stops before the footer. The time slide uses the remaining
        // space so day cells can grow without colliding with navigation or export controls.
        int safeBottom = height - FOOTER_HEIGHT - 8;
        int maxCellByHeight = Math.max(5, (safeBottom - gridTop - 6 * gap - 6) / 7);
        int maxCellByWidth = Math.max(5, (contentWidth - labelWidth - labelGap - (weeks - 1) * gap) / Math.max(1, weeks));
        int cell = Math.max(5, Math.min(24, Math.min(maxCellByHeight, maxCellByWidth)));
        int gridWidth = weeks * cell + (weeks - 1) * gap;
        int gridHeight = 7 * cell + 6 * gap;
        // Center the weekday labels and day grid together as one visual block.
        int heatmapWidth = labelWidth + labelGap + gridWidth;
        int heatmapLeft = (width - heatmapWidth) / 2;
        int left = heatmapLeft + labelWidth + labelGap;

        long max = 1L;
        int activeDays = 0;
        long totalDays = ChronoUnit.DAYS.between(start, end) + 1L;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            long seconds = snapshot.playtimeByDay.getOrDefault(day, 0L);
            if (seconds > 0) activeDays++;
            max = Math.max(max, seconds);
        }

        graphics.centeredText(font, "PLAYTIME BY DAY", width / 2, top, withAlpha(0xFFFFFFFF, Math.max(80, alpha * 3 / 4)));
        graphics.centeredText(font,
                activeDays + " of " + totalDays + " days played  •  brighter = more playtime",
                width / 2, top + 12, withAlpha(0xFFE3E3E3, Math.max(75, alpha * 3 / 4)));

        int labelRight = left - labelGap;
        graphics.text(font, "Mon", labelRight - font.width("Mon"), gridTop + 2, withAlpha(0xFFDADADA, alpha), false);
        graphics.text(font, "Wed", labelRight - font.width("Wed"), gridTop + 2 * (cell + gap) + 2, withAlpha(0xFFDADADA, alpha), false);
        graphics.text(font, "Fri", labelRight - font.width("Fri"), gridTop + 4 * (cell + gap) + 2, withAlpha(0xFFDADADA, alpha), false);

        for (LocalDate cursor = start; !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
            int week = (int) (ChronoUnit.DAYS.between(gridStart, cursor) / 7L);
            int day = cursor.getDayOfWeek().getValue() - 1;
            int x = left + week * (cell + gap);
            int y = gridTop + day * (cell + gap);
            long seconds = snapshot.playtimeByDay.getOrDefault(cursor, 0L);
            double intensity = seconds <= 0 ? 0.0 : Math.max(0.18, Math.sqrt((double) seconds / max));

            int color = seconds <= 0
                    ? withAlpha(0xFF000000, Math.max(42, alpha / 4))
                    : withAlpha(mix(0xFF2D6134, 0xFFE6FF8A, intensity), Math.max(105, alpha));

            graphics.fill(x, y, x + cell, y + cell, color);
        }
    }

    private void renderNavigationHint(GuiGraphicsExtractor graphics) {
        String hint = paused
                ? "◀ / ▶ navigate   •   SPACE next   •   P resume   •   ESC close"
                : "◀ / ▶ navigate   •   SPACE next   •   P pause   •   click sides";
        int footerTop = height - FOOTER_HEIGHT;
        graphics.centeredText(font, (slideIndex + 1) + " / " + slides.size(), width / 2, footerTop + 21, 0x99FFFFFF);
        graphics.centeredText(font, hint, width / 2, footerTop + 34, 0xCCFFFFFF);
    }

    private void renderExportFolder(GuiGraphicsExtractor graphics) {
        String location = WrappedPdfExporter.exportDirectory().toString();
        int footerTop = height - FOOTER_HEIGHT;
        String visiblePath = font.plainSubstrByWidth("Export folder: " + location, Math.max(120, width - 36));
        graphics.centeredText(font, visiblePath, width / 2, footerTop + 55, 0xFFB8B8C4);


        if (!exportStatus.isBlank()) {
            int color = exportStatus.startsWith("Saved") || exportStatus.startsWith("Opened")
                    ? 0xFFB9F6C5
                    : 0xFFFFB8B8;
            String visibleStatus = font.plainSubstrByWidth(exportStatus, Math.max(120, width - 36));
            graphics.centeredText(font, visibleStatus, width / 2, footerTop + 44, color);
        }
    }

    private void maybeAutoAdvance(long now) {
        if (paused || slides.size() <= 1 || slideIndex >= slides.size() - 1) return;
        if (now - slideStartedAt >= AUTO_ADVANCE_MS) setSlide(slideIndex + 1);
    }

    private List<Slide> buildSlides() {
        List<Slide> result = new ArrayList<>();
        if (!settings.enabled) {
            result.add(new Slide("SKYBLOCK WRAPPED", "Display disabled", "Enable Wrapped in NSM settings.", List.of(), 0, SlideKind.INTRO));
            return result;
        }

        int palette = 0;
        result.add(new Slide("Your Skyblock", "Wrapped", "A recap built from your SkyBlock history.",
                List.of(), palette++, SlideKind.INTRO));

        // TIME
        if (settings.time.enabled) {
            List<Stat> stats = new ArrayList<>();
            if (settings.time.playtime) stats.add(new Stat("Playtime", formatDuration(snapshot.playtimeSeconds)));
            if (settings.time.numberOfSessions) stats.add(new Stat("Sessions", formatCount(snapshot.sessions)));
            if (settings.time.longestSession) stats.add(new Stat("Longest session", formatDuration(snapshot.longestSessionSeconds)));
            if (settings.time.averageSessionLength) stats.add(new Stat("Average session", formatDuration(snapshot.averageSessionSeconds())));
            result.add(new Slide("", "Time well spent?", dateRange(), stats, palette++, SlideKind.TIME));
        }

        // FINANCIALS
        if (settings.financials.enabled && (snapshot.coinsEarned > 9 || snapshot.coinsSpent > 0 || snapshot.taxesPaid > 0 || snapshot.biggestPurchase > 0)) {
            List<Stat> stats = new ArrayList<>();
            if (settings.financials.coinsEarned) stats.add(new Stat("Coins earned", formatCoins(snapshot.coinsEarned)));
            if (settings.financials.coinsSpent) stats.add(new Stat("Coins spent", formatCoins(snapshot.coinsSpent)));
            if (settings.financials.netCoinChange) stats.add(new Stat("Net coins change", signedCoins(snapshot.netCoins())));
            if (settings.financials.mostExpensiveBuy && snapshot.biggestPurchase > 0) {
                stats.add(new Stat("Most expensive buy", formatCoins(snapshot.biggestPurchase) + subjectSuffix(snapshot.biggestPurchaseItem)));
            }
            if (settings.financials.taxesPaid) stats.add(new Stat("Taxes / fees paid", formatCoins(snapshot.taxesPaid)));
            if (!stats.isEmpty()) {
                result.add(new Slide("", "Financials", "Income, spending, and the purchase that hurt most.", stats, palette++, SlideKind.STANDARD));
            }
        }

        // MOST USED ITEMS, PETS, ARMOR
        if (settings.mostUsed.enabled && (!snapshot.armorSetSeconds.isEmpty() || !snapshot.petSeconds.isEmpty() || !snapshot.itemSeconds.isEmpty())) {
            List<Stat> stats = new ArrayList<>();
            if (settings.mostUsed.armorSet && !snapshot.armorSetSeconds.isEmpty()) {
                stats.add(usedStat("Armor set", snapshot.armorSetSeconds));
            }
            if (settings.mostUsed.bestFriendPet && !snapshot.petSeconds.isEmpty()) {
                stats.add(usedStat("Best friend", snapshot.petSeconds));
            }
            if (settings.mostUsed.item && !snapshot.itemSeconds.isEmpty()) {
                stats.add(usedStat("Most held item", snapshot.itemSeconds));
            }
            result.add(new Slide("", "Your favorite companions", "The things that spent the most time with you.", stats, palette++, SlideKind.STANDARD));
        }

        // DUNGEONS, min 3
        if (settings.dungeons.enabled && snapshot.dungeonRuns >= 3) {
            List<Stat> stats = new ArrayList<>();
            if (settings.dungeons.runs) stats.add(new Stat("Runs", formatCount(snapshot.dungeonRuns)));
            if (settings.dungeons.failedRuns) stats.add(new Stat("Failed runs", formatCount(snapshot.dungeonFailedRuns)));
            if (settings.dungeons.deaths) stats.add(new Stat("Deaths", formatCount(snapshot.dungeonDeaths)));
            if (settings.dungeons.mostPlayedFloor) stats.add(new Stat("Most played floor", fallback(snapshot.topInt(snapshot.dungeonFloorRuns))));
            if (settings.dungeons.fastestRun && snapshot.fastestDungeonSeconds > 0) {
                stats.add(new Stat("Fastest run", formatShortDuration(snapshot.fastestDungeonSeconds)));
            }
            if (SkyblockHistoryFeature.isPartyMemoryEnabled()) {
                if (settings.dungeons.uniquePlayersPlayedWith) stats.add(new Stat("Unique teammates", formatCount(snapshot.dungeonTeammateRuns.size())));
                if (settings.dungeons.playerPlayedWithMost && !snapshot.dungeonTeammateRuns.isEmpty()) {
                    stats.add(new Stat("Dungeon partner", topWithCount(snapshot.dungeonTeammateRuns)));
                }
                if (settings.dungeons.reunion && !snapshot.reunionGapDays.isEmpty()) {
                    Map.Entry<String, Integer> reunion = snapshot.reunionGapDays.entrySet().stream()
                            .max(Map.Entry.comparingByValue()).orElse(null);
                    if (reunion != null) stats.add(new Stat("Reunion", reunion.getKey() + " · " + reunion.getValue() + "d apart"));
                }
            }
            result.add(new Slide("", "The Catacombs", "Your dungeon chapter.", stats, palette++, SlideKind.STANDARD));
        }

        // SLAYERS, min 2
        if (settings.slayers.enabled && snapshot.slayerBosses >= 2) {
            List<Stat> stats = new ArrayList<>();
            if (settings.slayers.bossesKilled) stats.add(new Stat("Bosses killed", formatCount(snapshot.slayerBosses)));
            if (settings.slayers.mostPlayedSlayer) stats.add(new Stat("Most played Slayer", fallback(snapshot.topInt(snapshot.slayerBossesByType))));
            if (settings.slayers.fastestBoss && !snapshot.fastestSlayerMillis.isEmpty()) {
                Map.Entry<String, Long> fastest = snapshot.fastestSlayerMillis.entrySet().stream()
                        .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                        .min(Map.Entry.comparingByValue()).orElse(null);
                if (fastest != null) stats.add(new Stat("Fastest boss", fastest.getKey() + " · " + formatMillis(fastest.getValue())));
            }
            if (settings.slayers.rngDrops) stats.add(new Stat("RNG drops", formatCount(snapshot.slayerRngDrops)));
            result.add(new Slide("", "Slayers", "Kill, Summon, Kill, Summon, ...", stats, palette++, SlideKind.STANDARD));
        }

        // MINING, min 50 ores mined, 1 commission, or forge used
        if (settings.mining.enabled && (sumLong(snapshot.minedResources) >= 50 || snapshot.commissionsCompleted > 0 || snapshot.forgeBusySeconds > 0)) {
            List<Stat> stats = new ArrayList<>();
            if (settings.mining.mostMinedResource) stats.add(new Stat("Most mined resource", fallback(snapshot.topLong(snapshot.minedResources))));
            if (settings.mining.commissionsCompleted) stats.add(new Stat("Commissions", formatCount(snapshot.commissionsCompleted)));
            if (settings.mining.forgeWorkingTime) stats.add(new Stat("Forge working time", formatDuration(snapshot.forgeBusySeconds)));
            result.add(new Slide("", "Mining", "So much better than farming", stats, palette++, SlideKind.STANDARD));
        }

        // FARMING, min 100 crops broken, 1 contest, or 1 pest killed
        if (settings.farming.enabled && (snapshot.cropsBroken >= 100 || snapshot.jacobContests > 0 || snapshot.pestsKilled > 0)) {
            List<Stat> stats = new ArrayList<>();
            if (settings.farming.cropsBroken) stats.add(new Stat("Crops broken", formatCount(snapshot.cropsBroken)));
            if (settings.farming.mostFarmedCrop) stats.add(new Stat("Favorite crop", fallback(snapshot.topLong(snapshot.farmedCrops))));
            if (settings.farming.jacobContestsEntered) stats.add(new Stat("Jacobs contests", formatCount(snapshot.jacobContests)));
            if (settings.farming.pestsKilled) stats.add(new Stat("Pests cleared", formatCount(snapshot.pestsKilled)));
            result.add(new Slide("", "Farming", "So much better than mining", stats, palette++, SlideKind.STANDARD));
        }

        // FISHING, min 60 seconds fishing or 1 sea creature killed
        long fishingSeconds = snapshot.waterFishingSeconds + snapshot.lavaFishingSeconds;
        if (settings.fishing.enabled && (fishingSeconds >= 60 || snapshot.seaCreaturesKilled > 0)) {
            List<Stat> stats = new ArrayList<>();
            if (settings.fishing.preferredWaterOrLava) {
                String preference = snapshot.lavaFishingSeconds > snapshot.waterFishingSeconds ? "Lava" : "Water";
                long preferred = Math.max(snapshot.lavaFishingSeconds, snapshot.waterFishingSeconds);
                long percentage = fishingSeconds <= 0 ? 0 : Math.round(preferred * 100.0 / fishingSeconds);
                stats.add(new Stat("Preferred fishing", preference + " · " + percentage + "%"));
            }
            if (settings.fishing.seaCreaturesKilled) stats.add(new Stat("Sea creatures", formatCount(snapshot.seaCreaturesKilled)));
            if (settings.fishing.rareSeaCreatureCount) stats.add(new Stat("Rare sea creatures", formatCount(snapshot.rareSeaCreatures)));
            if (settings.fishing.deaths) stats.add(new Stat("Fishing deaths", formatCount(snapshot.fishingDeaths)));
            result.add(new Slide("", "Fishing", "Do you really enjoy this? " + formatDuration(fishingSeconds) + " with a rod out.", stats, palette++, SlideKind.STANDARD));
        }

        // FORAGING, min 50 logs broken
        if (settings.foraging.enabled && snapshot.logsBroken >= 50) {
            List<Stat> stats = new ArrayList<>();
            if (settings.foraging.logsBroken) stats.add(new Stat("Logs broken", formatCount(snapshot.logsBroken)));
            if (settings.foraging.mostChoppedWood) stats.add(new Stat("Most chopped wood", fallback(snapshot.topLong(snapshot.choppedWoods))));
            result.add(new Slide("", "Foraging", "The axe forgets, but the tree remembers.", stats, palette++, SlideKind.STANDARD));
        }

        // KUUDRA, min 1 run
        if (settings.kuudra.enabled && snapshot.kuudraRuns > 0) {
            List<Stat> stats = new ArrayList<>();
            if (settings.kuudra.runs) stats.add(new Stat("Runs", formatCount(snapshot.kuudraRuns)));
            if (settings.kuudra.failedRuns) stats.add(new Stat("Failed runs", formatCount(snapshot.kuudraFailedRuns)));
            if (settings.kuudra.favoriteTier && !snapshot.kuudraTierRuns.isEmpty()) stats.add(new Stat("Favorite Tier", fallback(snapshot.topInt(snapshot.kuudraTierRuns))));
            if (settings.kuudra.fastestRun && snapshot.fastestKuudraSeconds > 0) stats.add(new Stat("Fastest run", formatDuration(snapshot.fastestKuudraSeconds)));
            if (settings.kuudra.slowestRun && snapshot.slowestKuudraSeconds > 0) stats.add(new Stat("Slowest run", formatDuration(snapshot.slowestKuudraSeconds)));
            result.add(new Slide("", "Kuudra", "Got a little bored from dungeons?", stats, palette++, SlideKind.STANDARD));
        }

        // TIMED EVENTS, min 1 participation
        if (settings.timedEvents.enabled && settings.timedEvents.eventsParticipatedIn && !snapshot.timedEventInstances.isEmpty()) {
            List<Stat> stats = snapshot.timedEventInstances.entrySet().stream()
                    .map(entry -> Map.entry(entry.getKey(), entry.getValue().size()))
                    .sorted(Map.Entry.<String, Integer>comparingByValue()
                            .reversed()
                            .thenComparing(Map.Entry::getKey))
                    .limit(8)
                    .map(entry -> new Stat(
                            entry.getValue() <= 1
                                    ? "Participated once"
                                    : "Participated x" + entry.getValue(),
                            entry.getKey()
                    ))
                    .toList();
            result.add(new Slide("", "Events", "The events you participated in", stats, palette++, SlideKind.STANDARD));
        }

        // RIFT, at least motes spent or earned, or death
        if (settings.rift.enabled && (snapshot.riftMotesEarned > 0 || snapshot.riftMotesSpent > 0 || !snapshot.riftDeathsByCause.isEmpty())) {
            List<Stat> stats = new ArrayList<>();
            if (settings.rift.motesEarned) stats.add(new Stat("Motes earned", formatCount(snapshot.riftMotesEarned)));
            if (settings.rift.motesSpent) stats.add(new Stat("Motes spent", formatCount(snapshot.riftMotesSpent)));
            if (settings.rift.deathsByMob && !snapshot.riftDeathsByCause.isEmpty()) stats.add(new Stat("Top death cause", topWithCount(snapshot.riftDeathsByCause)));
            result.add(new Slide("", "The Rift", "Another dimension wont stop you", stats, palette++, SlideKind.STANDARD));
        }

        // Memory, chat messages
        if (settings.other.enabled) {
            List<Stat> stats = new ArrayList<>();
            boolean memory = SkyblockHistoryFeature.isPartyMemoryEnabled();
            if (memory && settings.other.uniquePlayersEncountered) stats.add(new Stat("Unique familiar players", formatCount(snapshot.encounterDays.size())));
            if (memory && settings.other.recurringPlayers) {
                long recurring = snapshot.encounterDays.values().stream().filter(value -> value != null && value >= 2).count();
                stats.add(new Stat("Recurring players", formatCount(recurring)));
            }
            if (memory && settings.other.regularPlayers) {
                long regulars = snapshot.encounterDays.values().stream().filter(value -> value != null && value >= 5).count();
                stats.add(new Stat("Seen on 5+ days", formatCount(regulars)));
            }

            if (memory && settings.other.playerSeenMost && !snapshot.encounteredPlayers.isEmpty()) stats.add(new Stat("Most encountered", topWithCount(snapshot.encounteredPlayers)));
            if (memory && settings.other.dungeonPartner && !snapshot.dungeonTeammateRuns.isEmpty()) stats.add(new Stat("Dungeon partner", topWithCount(snapshot.dungeonTeammateRuns)));
            if (memory && settings.other.reunion && !snapshot.reunionGapDays.isEmpty()) {
                Map.Entry<String, Integer> reunion = snapshot.reunionGapDays.entrySet().stream()
                        .max(Map.Entry.comparingByValue()).orElse(null);
                if (reunion != null) stats.add(new Stat("Longest reunion", reunion.getKey() + " · " + reunion.getValue() + "d apart"));
            }
            if (settings.other.chatMessagesSent) stats.add(new Stat("Chat messages sent", formatCount(snapshot.chatMessagesSent)));
            if (!stats.isEmpty()) {
                result.add(new Slide("", memory ? "Social" : "Other", memory ? "Your SkyBlock history was not solo." : "A few things outside the grind.", stats, palette++, SlideKind.STANDARD));
            }
        }

        List<Stat> ending = new ArrayList<>();
        ending.add(new Stat("Playtime", formatDuration(snapshot.playtimeSeconds)));
        if (snapshot.coinsEarned > 0 || snapshot.coinsSpent > 0) ending.add(new Stat("Net coins", signedCoins(snapshot.netCoins())));
        String secondHome = topKnownArea(snapshot.areaSeconds);
        if (!snapshot.areaSeconds.isEmpty()) ending.add(new Stat("Second home", secondHome));
        result.add(new Slide("That was your Skyblock!", "See you next time.", dateRange(), ending, palette++, SlideKind.STANDARD));

        return result;
    }

    private static String topKnownArea(Map<String, Long> areas) {
        return areas.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .filter(entry -> !entry.getKey().equalsIgnoreCase("(Unknown)"))
                .filter(entry -> !entry.getKey().equalsIgnoreCase("Unknown"))
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("");
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (event.x() < width * 0.38) previousSlide();
            else nextSlide();
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        if (key == GLFW.GLFW_KEY_RIGHT || key == GLFW.GLFW_KEY_D || key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            nextSlide();
            return true;
        }
        if (key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_A) {
            previousSlide();
            return true;
        }
        if (key == GLFW.GLFW_KEY_SPACE) {
            nextSlide();
            return true;
        }
        if (key == GLFW.GLFW_KEY_P) {
            togglePause();
            return true;
        }
        if (key == GLFW.GLFW_KEY_HOME) {
            setSlide(0);
            return true;
        }
        if (key == GLFW.GLFW_KEY_END) {
            setSlide(slides.size() - 1);
            return true;
        }
        return super.keyPressed(event);
    }

    private void togglePause() {
        long now = System.currentTimeMillis();
        if (paused) {
            slideStartedAt +=Math.max(0L, now - pausedAt);
            paused = false;
            pausedAt = 0L;
        } else {
            paused = true;
            pausedAt = now;
        }
    }

    private void nextSlide() {
        if (slideIndex >= slides.size() - 1) return;
        setSlide(slideIndex + 1);
    }

    private void previousSlide() {
        if (slideIndex <= 0) return;
        setSlide(slideIndex - 1);
    }

    private void setSlide(int index) {
        slideIndex = Math.max(0, Math.min(index, slides.size() - 1));
        slideStartedAt = System.currentTimeMillis();
        paused = false;
        pausedAt = 0L;
    }

    private void exportPdf() {
        try {
            Path exported = WrappedPdfExporter.export(month, snapshot, slides);
            exportStatus = "Saved: " + exported.getFileName();
        } catch (IOException exception) {
            System.err.println("[NSM Wrapped] Could not export PDF: " + exception.getMessage());
            exportStatus = "Export failed: " + exception.getMessage();
        }
    }

    private void openExportFolder() {
        try {
            Path directory = WrappedPdfExporter.ensureExportDirectory();
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                exportStatus = "Could not open export folder on this system";
                return;
            }
            Desktop.getDesktop().open(directory.toFile());
            exportStatus = "Opened: config/nicos_super_mods/wrapped_exports";
        } catch (Exception exception) {
            System.err.println("[NSM Wrapped] Could not open export folder: " + exception.getMessage());
            exportStatus = "Could not open export folder";
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void renderScaledCentered(GuiGraphicsExtractor graphics, String text, int centerX, int y, float scale, int color, boolean shadow) {
        if (text == null || text.isBlank()) return;
        String visible = font.plainSubstrByWidth(text, Math.max(40, (int) ((width - 36) / Math.max(0.1f, scale))));
        float logicalWidth = font.width(visible);
        graphics.pose().pushMatrix();
        graphics.pose().translate(centerX - logicalWidth * scale / 2.0f, y);
        graphics.pose().scale(scale, scale);
        graphics.text(font, visible, 0, 0, color, shadow);
        graphics.pose().popMatrix();
    }

    private float titleScale(String text) {
        int length = text == null ? 0 : text.length();
        if (length <= 13) return 2.35f;
        if (length <= 20) return 1.85f;
        return 1.45f;
    }

    private String dateRange() {
        return snapshot.start.format(DATE) + " - " + snapshot.end.format(DATE);
    }

    private static Stat usedStat(String label, Map<String, Long> map) {
        Map.Entry<String, Long> top = map.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .max(Map.Entry.comparingByValue()).orElse(null);
        if (top == null) return new Stat(label, "Unknown");
        return new Stat(label, top.getKey() + " · " + formatDuration(top.getValue()));
    }

    private static String topWithCount(Map<String, Integer> map) {
        Map.Entry<String, Integer> top = map.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .max(Map.Entry.comparingByValue()).orElse(null);
        return top == null ? "Unknown" : top.getKey() + " · " + formatCount(top.getValue());
    }

    private static long sumLong(Map<String, Long> values) {
        long total = 0L;
        for (Long value : values.values()) if (value != null && value > 0) total += value;
        return total;
    }

    private static String fallback(String value) {
        return value == null || value.isBlank() ? "Unknown" : value;
    }

    private static String subjectSuffix(String subject) {
        return subject == null || subject.isBlank() ? "" : " · " + subject;
    }

    private static String formatCount(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String formatCoins(long value) {
        long abs = Math.abs(value);
        if (abs >= 1_000_000_000L) return trimDecimal(value / 1_000_000_000L) + "b";
        if (abs >= 1_000_000L) return trimDecimal(value / 1_000_000L) + "m";
        if (abs >= 1_000L) return trimDecimal(value / 1_000L) + "k";
        return formatCount(value);
    }

    private static String signedCoins(long value) {
        return (value > 0 ? "+" : "") + formatCoins(value);
    }

    private static String trimDecimal(double value) {
        double rounded = Math.round(value * 10.0) / 10.0;
        if (Math.rint(rounded) == rounded) return String.format(Locale.ROOT, "%.0f", rounded);
        return String.format(Locale.ROOT, "%.1f", rounded);
    }

    private static String formatDuration(long seconds) {
        if (seconds <= 0) return "0m";
        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return Math.max(1L, minutes) + "m";
    }

    private static String formatShortDuration(long seconds) {
        if (seconds <= 0) return "-";
        long minutes = seconds / 60L;
        long remainder = seconds % 60L;
        return minutes > 0 ? minutes + "m " + remainder + "s" : remainder + "s";
    }

    private static String formatMillis(long millis) {
        if (millis <= 0) return "-";
        long seconds = millis / 1000L;
        long tenths = (millis % 1000L) / 100L;
        return seconds + "." + tenths + "s";
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static int withAlpha(int color, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private static int mix(int from, int to, double t) {
        double clamped = clamp01(t);
        int fr = (from >> 16) & 0xFF;
        int fg = (from >> 8) & 0xFF;
        int fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF;
        int tg = (to >> 8) & 0xFF;
        int tb = to & 0xFF;
        int r = (int) Math.round(fr + (tr - fr) * clamped);
        int g = (int) Math.round(fg + (tg - fg) * clamped);
        int b = (int) Math.round(fb + (tb - fb) * clamped);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    enum SlideKind {
        INTRO,
        TIME,
        STANDARD
    }

    record Stat(String label, String value) { }

    record Slide(
            String kicker,
            String title,
            String subtitle,
            List<Stat> stats,
            int palette,
            SlideKind kind
    ) { }
}

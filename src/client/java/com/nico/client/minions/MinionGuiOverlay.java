package com.nico.client.minions;

import com.nico.client.configuration.NsmConfig;
import com.nico.client.hud.HudElement;
import com.nico.client.hud.HudLayoutManager;
import com.nico.client.minions.MinionOutputEstimator;
import com.nico.client.minions.base.*;
import com.nico.client.utils.BazaarService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MinionGuiOverlay {
    private static final int PANEL_WIDTH = 230;
    private static final int PANEL_PADDING = 8;
    private static final long REFRESH_MILLIS = 15_000L;

    private final OpenMinionGuiDetector detector;
    private final MinionOutputEstimator estimator;

    private final MinionUpgradeEstimator upgradeEstimator;
    private volatile MinionUpgradeEstimate currentUpgradeEstimate;

    private final BazaarService bazaarService;
    private final HudLayoutManager layoutManager;

    private final ExecutorService estimateExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Nico Minion ROI Estimator");
        thread.setDaemon(true);
        return thread;
    });

    private volatile String currentSignature = "";
    private volatile MinionEstimate currentEstimate;
    private volatile String currentError = "";
    private volatile boolean estimateLoading = false;
    private volatile long nextRefreshAt = 0L;

    public MinionGuiOverlay(
            MinionDataRegistry registry,
            MinionOutputEstimator estimator,
            BazaarService bazaarService,
            HudLayoutManager layoutManager
    ) {
        this.detector = new OpenMinionGuiDetector(registry);
        this.estimator = estimator;
        this.upgradeEstimator = new MinionUpgradeEstimator(estimator);
        this.bazaarService = bazaarService;
        this.layoutManager = layoutManager;
    }

    public void onRenderPost(Screen screen, GuiGraphics graphics) {
        Optional<DetectedMinionWindow> optionalWindow = detector.detect(screen);

        if (optionalWindow.isEmpty()) {
            return;
        }

        DetectedMinionWindow window = optionalWindow.get();

        requestEstimateIfNeeded(window);

        renderPanel(graphics, window);
    }

    private void requestEstimateIfNeeded(DetectedMinionWindow window) {
        long now = System.currentTimeMillis();
        String signature = window.signature();

        boolean signatureChanged = !signature.equals(currentSignature);
        boolean refreshDue = now >= nextRefreshAt;

        if (!signatureChanged && !refreshDue) {
            return;
        }

        if (estimateLoading && signature.equals(currentSignature)) {
            return;
        }

        currentSignature = signature;
        currentError = "";
        estimateLoading = true;
        nextRefreshAt = now + REFRESH_MILLIS;

        if (signatureChanged) {
            currentEstimate = null;
            currentUpgradeEstimate = null;
        }

        CompletableFuture
                .supplyAsync(() -> estimate(window), estimateExecutor)
                .whenComplete((bundle, throwable) -> {
                    if (!signature.equals(currentSignature)) {
                        return;
                    }

                    estimateLoading = false;

                    if (throwable != null) {
                        currentEstimate = null;
                        currentUpgradeEstimate = null;
                        currentError = rootMessage(throwable);
                        return;
                    }

                    currentEstimate = bundle.outputEstimate();
                    currentUpgradeEstimate = bundle.upgradeEstimate();
                    currentError = "";
                });
    }

    private EstimateBundle estimate(DetectedMinionWindow window) {
        try {
            MinionEstimate outputEstimate =
                    estimator.estimate(window.setup(), bazaarService);

            Optional<MinionUpgradeEstimate> upgradeEstimate =
                    upgradeEstimator.estimateNextUpgrade(
                            window.minionData(),
                            window.setup(),
                            bazaarService
                    );

            return new EstimateBundle(
                    outputEstimate,
                    upgradeEstimate.orElse(null)
            );
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    private void renderPanel(GuiGraphics graphics, DetectedMinionWindow window) {
        if (!NsmConfig.INSTANCE.island.minionInfo.enabled) return;

        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;

        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();

        HudElement element = layoutManager.get(HudLayoutManager.MINION_OUTPUT);
        int x = element != null ? element.getX() : screenWidth - PANEL_WIDTH - 8;
        int y = element != null ? element.getY() : 12;
        double scale = element != null ? element.getScale() : 1.0D;

        List<String> lines = buildLines(window, currentEstimate, currentUpgradeEstimate, currentError, estimateLoading);

        int naturalPanelWidth = PANEL_WIDTH;
        int naturalPanelHeight = Math.min(
                screenHeight - 24,
                PANEL_PADDING * 2 + 12 + lines.size() * 10
        );

        if (element != null) {
            boolean wasSeen = element.hasBeenSeen();
            element.setMeasuredSize(naturalPanelWidth, naturalPanelHeight);

            if (!wasSeen) {
                layoutManager.save();
            }
        }

        graphics.pose().pushMatrix();
        graphics.pose().translate((float) x, (float) y);
        graphics.pose().scale((float) scale, (float) scale);

        int panelX = 0;
        int panelY = 0;

        graphics.fill(
                panelX,
                panelY,
                panelX + naturalPanelWidth,
                panelY + naturalPanelHeight,
                0xCC202020
        );

        graphics.renderOutline(
                panelX,
                panelY,
                naturalPanelWidth,
                naturalPanelHeight,
                0xFF888888
        );

        int textX = PANEL_PADDING;
        int textY = PANEL_PADDING;

        graphics.drawString(font, "Minion Output", textX, textY, 0xFFEFEFEF, true);
        textY += 12;

        int maxTextWidth = naturalPanelWidth - PANEL_PADDING * 2;

        for (String line : lines) {
            if (textY > naturalPanelHeight - 11) {
                graphics.drawString(font, "...", textX, textY, 0xFFAAAAAA, true);
                break;
            }

            graphics.drawString(
                    font,
                    trimToWidth(font, line, maxTextWidth),
                    textX,
                    textY,
                    colorForLine(line),
                    true
            );

            textY += 10;
        }

        graphics.pose().popMatrix();
    }

    private List<String> buildLines(
            DetectedMinionWindow window,
            MinionEstimate estimate,
            MinionUpgradeEstimate upgradeEstimate,
            String error,
            boolean loading
    ) {
        List<String> lines = new ArrayList<>();
        MinionData data = window.minionData();

        lines.add(data.displayName() + " " + toRoman(window.tier()));
        lines.add("Tier: " + window.tier() + "/" + data.maxTier());

        if (!data.simpleEstimatorEnabled()) {
            lines.add("Warning: special minion estimate");
        }

        lines.add("");

        if (loading && estimate == null) {
            lines.add("Loading Bazaar prices...");
        }

        if (error != null && !error.isBlank()) {
            lines.add("Error: " + error);
        }

        if (estimate != null) {
            lines.add("Coins/day: " + formatCoins(estimate.coinsPerDay()));
            lines.add("Base action: " + formatSeconds(estimate.baseSecondsBetweenActions()));
            lines.add("Effective action: " + formatSeconds(estimate.effectiveSecondsBetweenActions()));
            lines.add("Speed bonus: +" + formatNumber(estimate.totalAdditiveSpeedPercent()) + "%");
            lines.add("Output mult: x" + formatNumber(estimate.outputMultiplier()));
            lines.add("Cycles/day: " + formatNumber(estimate.cyclesPerDay()));
        }

        if (!window.detectedModifiers().isEmpty()) {
            lines.add("");
            lines.add("Detected modifiers:");

            for (DetectedMinionModifier modifier : window.detectedModifiers()) {
                String prefix = modifier.isModeled() ? "+ " : "! ";
                lines.add(prefix + modifier.getDisplayName());

                if (!modifier.isModeled()) {
                    lines.add("  " + modifier.getNote());
                }
            }
        }

        if (estimate != null) {
            if (!estimate.itemsPerDay().isEmpty()) {
                lines.add("");
                lines.add("Items/day:");

                for (Map.Entry<String, Double> entry : topEntries(estimate.itemsPerDay(), 5)) {
                    lines.add("- " + entry.getKey() + ": " + formatNumber(entry.getValue()));
                }
            }

            if (!estimate.coinValueByProduct().isEmpty()) {
                lines.add("");
                lines.add("Value/day:");

                for (Map.Entry<String, Double> entry : topEntries(estimate.coinValueByProduct(), 5)) {
                    lines.add("- " + entry.getKey() + ": " + formatCoins(entry.getValue()));
                }
            }

            if (!estimate.missingBazaarPrices().isEmpty()) {
                lines.add("");
                lines.add("Missing prices:");

                for (String productId : estimate.missingBazaarPrices()) {
                    lines.add("- " + productId);
                }
            }
        }

        if (NsmConfig.INSTANCE.island.minionInfo.showUpgradeRoi) {
            if (upgradeEstimate != null) {
                lines.add("");
                lines.add("Next Upgrade:");
                lines.add("Tier: " + toRoman(upgradeEstimate.fromTier()) + " → " + toRoman(upgradeEstimate.toTier()));

                for (MinionUpgradeMaterial material : upgradeEstimate.materials()) {
                    lines.add(
                            "Needs: " +
                                    formatNumber(material.amount()) +
                                    "x " +
                                    material.itemName()
                    );
                }

                lines.add("Cost: " + formatCoins(upgradeEstimate.upgradeCost()));
                lines.add("Profit gain: " + formatCoins(upgradeEstimate.extraCoinsPerDay()) + "/day");

                if (upgradeEstimate.canPayBack()) {
                    lines.add("Pays back: " + formatDays(upgradeEstimate.paybackDays()));
                } else {
                    lines.add("Pays back: never");
                }
            } else if (window.tier() < window.minionData().maxTier()) {
                lines.add("");
                lines.add("Next Upgrade:");
                lines.add("No recipe data for this tier");
            } else {
                lines.add("");
                lines.add("Next Upgrade:");
                lines.add("Max tier");
            }
        }

        return lines;
    }

    private static List<Map.Entry<String, Double>> topEntries(Map<String, Double> map, int limit) {
        return map.entrySet()
                .stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    private static int colorForLine(String line) {
        if (line.startsWith("Error:")) {
            return 0xFFFF5555;
        }

        if (line.startsWith("Warning:") || line.startsWith("! ")) {
            return 0xFFFFAA00;
        }

        if (line.startsWith("Coins/day:")) {
            return 0xFF55FF55;
        }

        if (line.endsWith(":") || line.equals("Minion Output")) {
            return 0xFF55AAFF;
        }

        return 0xFFEFEFEF;
    }

    private static String trimToWidth(Font font, String value, int maxWidth) {
        if (font.width(value) <= maxWidth) {
            return value;
        }

        String suffix = "...";
        int suffixWidth = font.width(suffix);
        String trimmed = value;

        while (!trimmed.isEmpty() && font.width(trimmed) + suffixWidth > maxWidth) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        return trimmed + suffix;
    }

    private static String formatCoins(double value) {
        return formatCompact(value) + " coins";
    }

    private static String formatSeconds(double value) {
        return String.format(Locale.US, "%.2fs", value);
    }

    private static String formatNumber(double value) {
        if (Math.abs(value) >= 100.0D) {
            return String.format(Locale.US, "%.0f", value);
        }

        if (Math.abs(value) >= 10.0D) {
            return String.format(Locale.US, "%.1f", value);
        }

        return String.format(Locale.US, "%.2f", value);
    }

    private static String formatCompact(double value) {
        double abs = Math.abs(value);

        if (abs >= 1_000_000_000.0D) {
            return String.format(Locale.US, "%.2fb", value / 1_000_000_000.0D);
        }

        if (abs >= 1_000_000.0D) {
            return String.format(Locale.US, "%.2fm", value / 1_000_000.0D);
        }

        if (abs >= 1_000.0D) {
            return String.format(Locale.US, "%.1fk", value / 1_000.0D);
        }

        return String.format(Locale.US, "%.0f", value);
    }

    private static String formatDays(double days) {
        if (!Double.isFinite(days)) {
            return "never";
        }

        if (days < 1.0D) {
            return String.format(Locale.US, "%.1f hours", days * 24.0D);
        }

        return String.format(Locale.US, "%.1f days", days);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;

        while (current.getCause() != null) {
            current = current.getCause();
        }

        String message = current.getMessage();

        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }

        return message;
    }

    private static String toRoman(int number) {
        return switch (number) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            case 11 -> "XI";
            case 12 -> "XII";
            default -> String.valueOf(number);
        };
    }

    private record EstimateBundle(
            MinionEstimate outputEstimate,
            MinionUpgradeEstimate upgradeEstimate
    ) {

    }
}
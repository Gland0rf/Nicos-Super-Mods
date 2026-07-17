package com.nico.client.minions;

import com.nico.client.minions.base.*;
import com.nico.client.minions.base.MinionData;
import com.nico.client.utils.BazaarService;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OpenMinionGuiDetector {
    private static final Pattern MINION_TITLE_PATTERN =
            Pattern.compile("^\\s*(.+?)\\s+Minion\\s+([IVXLCDM]+)\\s*$", Pattern.CASE_INSENSITIVE);

    private final MinionDataRegistry registry;

    public OpenMinionGuiDetector(MinionDataRegistry registry) {
        this.registry = registry;
    }

    public Optional<DetectedMinionWindow> detect(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return Optional.empty();
        }

        String title = clean(screen.getTitle().getString());
        Matcher matcher = MINION_TITLE_PATTERN.matcher(title);

        if (!matcher.matches()) {
            return Optional.empty();
        }

        String minionNamePart = matcher.group(1).trim();
        String romanTier = matcher.group(2).trim();

        String minionId = toMinionId(minionNamePart);
        int tier = romanToInt(romanTier);

        if (tier <= 0) {
            return Optional.empty();
        }

        Optional<MinionData> optionalData = registry.getDataById(minionId);

        if (optionalData.isEmpty()) {
            return Optional.empty();
        }

        MinionData minionData = optionalData.get();

        MinionSetup setup = new MinionSetup(minionData.definition(), tier)
                .setPriceMode(BazaarService.PriceMode.INSTANT_SELL);

        List<DetectedMinionModifier> detectedModifiers = detectModifiers(containerScreen, minionData);

        for (DetectedMinionModifier detectedModifier : detectedModifiers) {
            if (detectedModifier.isModeled()) {
                setup.addModifier(detectedModifier.getModifier());
            }
        }

        return Optional.of(new DetectedMinionWindow(
                title,
                minionId,
                tier,
                minionData,
                setup,
                detectedModifiers
        ));
    }

    private List<DetectedMinionModifier> detectModifiers(
            AbstractContainerScreen<?> containerScreen,
            MinionData minionData
    ) {
        List<DetectedMinionModifier> result = new ArrayList<>();

        for (Slot slot : containerScreen.getMenu().slots) {
            if (slot == null || !slot.hasItem()) {
                continue;
            }

            ItemStack stack = slot.getItem();

            if (stack == null || stack.isEmpty()) {
                continue;
            }

            String itemName = clean(stack.getHoverName().getString());

            Optional<DetectedMinionModifier> modifier =
                    MinionModifierFactory.fromItemName(itemName, minionData.definition());

            modifier.ifPresent(result::add);
        }

        return result;
    }

    private static String toMinionId(String minionNamePart) {
        return clean(minionNamePart)
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replaceAll("§.", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static int romanToInt(String roman) {
        int result = 0;
        int previous = 0;

        String upper = roman.toUpperCase(Locale.ROOT);

        for (int i = upper.length() - 1; i >= 0; i--) {
            int value = romanValue(upper.charAt(i));

            if (value < previous) {
                result -= value;
            } else {
                result += value;
            }

            previous = value;
        }

        return result;
    }

    private static int romanValue(char character) {
        return switch (character) {
            case 'I' -> 1;
            case 'V' -> 5;
            case 'X' -> 10;
            case 'L' -> 50;
            case 'C' -> 100;
            case 'D' -> 500;
            case 'M' -> 1000;
            default -> 0;
        };
    }
}
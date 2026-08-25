package com.nico.client.utils.tradeprot.valuation;

import com.nico.client.utils.SkyblockItemResolver;
import com.nico.client.utils.tradeprot.valuation.records.AppliedGemstone;
import com.nico.client.utils.tradeprot.valuation.records.GemstoneType;
import com.nico.client.utils.tradeprot.valuation.records.SkyblockItemData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.*;

public final class SkyblockItemDataReader {
    public SkyblockItemData read(ItemStack stack) {
        SkyblockItemResolver.ItemIdentity identity = SkyblockItemResolver.resolveIdentity(stack);
        if (stack == null || stack.isEmpty()) return empty(identity);

        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) return empty(identity);

        CompoundTag root = customData.copyTag();
        Object extra = root.getCompound("ExtraAttributes").map(value -> (Object) value).orElse(null);

        return new SkyblockItemData(
                identity.internalId(), identity.displayName(), stack.getCount(),
                NbtView.string(extra, "modifier"),
                NbtView.integer(extra, "rarity_upgrades"),
                NbtView.integer(extra, "hot_potato_count"),
                NbtView.integer(extra, "upgrade_level"),
                NbtView.integer(extra, "art_of_war_count"),
                NbtView.integer(extra, "farming_for_dummies_count"),
                NbtView.integer(extra, "tuned_transmission"),
                NbtView.integer(extra, "wood_singularity_count") > 0 || NbtView.booleanValue(extra, "wood_singularity"),
                firstNonBlank(NbtView.string(extra, "power_ability_scroll"), NbtView.string(extra, "power_scroll")),
                readIntMap(NbtView.compound(extra, "enchantments")),
                readGemstones(NbtView.compound(extra, "gems")),
                readAbilityScrolls(extra),
                readRaw(extra)
        );
    }

    private static List<AppliedGemstone> readGemstones(Object gems) {
        if (gems == null) return List.of();

        List<AppliedGemstone> result = new ArrayList<>();
        for (String key : NbtView.keys(gems)) {
            String slot = normalize(key);
            if (slot.isBlank() || slot.endsWith("_GEM") || slot.equals("UNLOCKED_SLOTS")) continue;

            String quality = normalize(NbtView.string(gems, key));
            Object slotData = NbtView.compound(gems, key);
            if (quality.isBlank() && slotData != null) {
                quality = firstNonBlank(
                        normalize(NbtView.string(slotData, "quality")),
                        normalize(NbtView.string(slotData, "tier"))
                );
            }
            if (quality.isBlank()) continue;

            String explicitType = normalize(NbtView.string(gems, key + "_gem"));
            if (explicitType.isBlank() && slotData != null) {
                explicitType = firstNonBlank(
                        normalize(NbtView.string(slotData, "gem")),
                        normalize(NbtView.string(slotData, "type"))
                );
            }

            GemstoneType type = GemstoneType.fromToken(explicitType)
                    .or(() -> GemstoneType.fromToken(slot))
                    .orElse(null);

            AppliedGemstone gemstone = new AppliedGemstone(slot, type, quality);
            if (gemstone.valid()) result.add(gemstone);
        }
        return List.copyOf(result);
    }

    private static List<String> readAbilityScrolls(Object extra) {
        List<String> values = NbtView.stringList(extra, "ability_scroll");
        return values.isEmpty() ? NbtView.stringList(extra, "ability_scrolls") : values;
    }

    private static Map<String, Integer> readIntMap(Object compound) {
        if (compound == null) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String key : NbtView.keys(compound)) {
            int value = NbtView.integer(compound, key);
            if (value > 0) result.put(normalize(key), value);
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> readRaw(Object compound) {
        if (compound == null) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : NbtView.keys(compound)) {
            String value = NbtView.scalarString(NbtView.raw(compound, key));
            if (!value.isBlank()) result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private static SkyblockItemData empty(SkyblockItemResolver.ItemIdentity identity) {
        return new SkyblockItemData(identity.internalId(), identity.displayName(), 1, "", 0, 0, 0,
                0, 0, 0, false, "", Map.of(),
                List.of(), List.of(), Map.of());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second == null ? "" : second;
    }
}

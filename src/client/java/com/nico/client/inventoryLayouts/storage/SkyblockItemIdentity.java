package com.nico.client.inventoryLayouts.storage;

import com.nico.client.inventoryLayouts.core.InventoryLayoutSlot;
import com.nico.client.utils.SkyblockItemResolver;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

public final class SkyblockItemIdentity {
    private SkyblockItemIdentity() {
    }

    public static InventoryLayoutSlot capture(int inventorySlot, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("Cannot capture an empty inventory slot");
        }

        SkyblockItemResolver.ItemIdentity identity =
                SkyblockItemResolver.resolveIdentity(stack);

        String baseItemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();

        return new InventoryLayoutSlot(
                inventorySlot,
                baseItemId,
                identity.internalId(),
                identity.displayName(),
                stack.getCount()
        );
    }

    public static boolean matches(
            InventoryLayoutSlot expected,
            ItemStack actual,
            boolean matchStackCounts
    ) {
        if (expected == null || actual == null || actual.isEmpty()) {
            return false;
        }

        SkyblockItemResolver.ItemIdentity actualIdentity =
                SkyblockItemResolver.resolveIdentity(actual);

        String expectedSkyblockId = expected.skyblockItemId();

        if (!expectedSkyblockId.isBlank()) {
            // The Hypixel internal ID is authoritative when the saved item has one.
            if (!expectedSkyblockId.equalsIgnoreCase(actualIdentity.internalId())) {
                return false;
            }
        } else {
            // Vanilla/unknown items fall back to registry ID plus normalized name.
            String actualBaseItemId = BuiltInRegistries.ITEM
                    .getKey(actual.getItem())
                    .toString();

            if (!expected.baseItemId().equals(actualBaseItemId)) {
                return false;
            }

            String expectedName = normalizeName(expected.displayName());
            String actualName = normalizeName(actualIdentity.displayName());

            if (!expectedName.equals(actualName)) {
                return false;
            }
        }

        return !matchStackCounts || actual.getCount() == expected.count();
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
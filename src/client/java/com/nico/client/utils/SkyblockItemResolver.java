package com.nico.client.utils;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Extracts stable Hypixel item metadata from an ItemStack. */
public final class SkyblockItemResolver {
    private static final List<String> EXTRA_ATTRIBUTE_KEYS = List.of(
            "ExtraAttributes",
            "extra_attributes",
            "extraAttributes"
    );

    private SkyblockItemResolver() { }

    public static ItemIdentity resolveIdentity(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new ItemIdentity("", "", "");
        }

        String internalId = readAttribute(stack, "id");
        String modifier = readAttribute(stack, "modifier");

        String displayName = cleanDisplayName(stack.getHoverName().getString());

        return new ItemIdentity(internalId, displayName, modifier);
    }

    private static String readAttribute(ItemStack stack, String key) {
        try {
            CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
            if (customData != null) {
                String value = readAttribute(customData.copyTag(), key);
                if (!value.isBlank()) return value;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Keep compatibility paths for transformed mappings/older representations.
        }

        String legacy = readAttributeFromUnknownTag(tryLegacyTag(stack), key);
        if (!legacy.isBlank()) return legacy;

        return readAttributeFromUnknownTag(tryCustomDataReflectively(stack), key);
    }

    private static String readAttribute(CompoundTag root, String key) {
        if (root == null) return "";

        String direct = root.getString(key).orElse("").trim();
        if (!direct.isBlank()) return direct;

        for (String extraKey : EXTRA_ATTRIBUTE_KEYS) {
            Optional<CompoundTag> extra = root.getCompound(extraKey);
            if (extra.isEmpty()) continue;

            String value = extra.get().getString(key).orElse("").trim();
            if (!value.isBlank()) return value;
        }

        return "";
    }

    private static String readAttributeFromUnknownTag(Object rootTag, String key) {
        Object root = unwrapOptional(rootTag);
        if (root instanceof CompoundTag compoundTag) {
            return readAttribute(compoundTag, key);
        }
        if (root == null) {
            return "";
        }

        String direct = readString(root, key);
        if (!direct.isBlank()) return direct;

        for (String extraKey : EXTRA_ATTRIBUTE_KEYS) {
            Object extraAttributes = invokeKeyMethod(root, List.of("getCompound", "getCompoundOrEmpty"), extraKey);
            extraAttributes = unwrapOptional(extraAttributes);
            if (extraAttributes == null) continue;

            String value = readString(extraAttributes, key);
            if (!value.isBlank()) return value;
        }

        return "";
    }

    private static String readString(Object compound, String key) {
        Object value = invokeKeyMethod(compound, List.of("getString", "getStringOr"), key);
        value = unwrapOptional(value);
        return value instanceof String string ? string.trim() : "";
    }

    private static Object tryLegacyTag(ItemStack stack) {
        try {
            Method method = stack.getClass().getMethod("getTag");
            return unwrapOptional(method.invoke(stack));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object tryCustomDataReflectively(ItemStack stack) {
        try {
            Class<?> dataComponents = Class.forName("net.minecraft.core.component.DataComponents");
            Object componentType = dataComponents.getField("CUSTOM_DATA").get(null);

            Method getter = findCompatibleGetter(stack.getClass(), componentType);
            if (getter == null) {
                return null;
            }

            Object customData = unwrapOptional(getter.invoke(stack, componentType));
            if (customData == null) {
                return null;
            }

            for (String methodName : List.of("copyTag", "getUnsafe", "tag")) {
                try {
                    Method tagMethod = customData.getClass().getMethod(methodName);
                    Object result = unwrapOptional(tagMethod.invoke(customData));
                    if (result != null) {
                        return result;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try the next mapping name.
                }
            }

            return customData;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Method findCompatibleGetter(Class<?> stackClass, Object componentType) {
        for (Method method : stackClass.getMethods()) {
            if (!method.getName().equals("get") || method.getParameterCount() != 1) {
                continue;
            }

            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter.isInstance(componentType)
                    || parameter.isAssignableFrom(componentType.getClass())) {
                return method;
            }
        }
        return null;
    }

    private static Object invokeKeyMethod(Object target, List<String> names, String key) {
        for (String name : names) {
            for (Method method : target.getClass().getMethods()) {
                if (!method.getName().equals(name)
                        || method.getParameterCount() == 0
                        || method.getParameterTypes()[0] != String.class) {
                    continue;
                }

                try {
                    if (method.getParameterCount() == 1) {
                        return method.invoke(target, key);
                    }

                    if (method.getParameterCount() == 2
                            && method.getParameterTypes()[1] == String.class) {
                        return method.invoke(target, key, "");
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try another overload or mapping name.
                }
            }
        }
        return null;
    }

    private static Object unwrapOptional(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private static String cleanDisplayName(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        return input
                .replaceAll("(?i)\\u00a7[0-9A-FK-ORX]", "")
                .replaceAll("[\\u278A-\\u2793\\u272A\\u2726\\u2605\\u2606]+", "")
                .replaceFirst("(?i)^\\s*\\[\\s*Lvl\\s+\\d+\\s*]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String stripModifierPrefix(String displayName, String modifier) {
        String reforgePrefix = formatModifier(modifier);
        if (displayName.isBlank() || reforgePrefix.isBlank()) return displayName;

        return displayName.replaceFirst(
                "(?i)^" + Pattern.compile(reforgePrefix) + "\\s+",
                ""
        ).trim();
    }

    private static String formatModifier(String modifier) {
        if (modifier == null || modifier.isBlank()) {
            return "";
        }

        String normalized = modifier
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceFirst("_(sword|bow)$", "")
                .replace('_', ' ');

        StringBuilder result = new StringBuilder();

        for (String word : normalized.split("\\s+")) {
            if (word.isBlank()) {
                continue;
            }

            if (!result.isEmpty()) {
                result.append(' ');
            }

            result.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1));
        }

        return result.toString();
    }

    public record ItemIdentity(String internalId, String displayName, String modifier) {
        public ItemIdentity(String internalId, String displayName) {
            this(internalId, displayName, "");
        }

        public ItemIdentity {
            internalId = internalId == null ? "" : internalId.trim();
            displayName = displayName == null ? "" : displayName.trim();
            modifier = modifier == null ? "" : modifier.trim();
        }

        public boolean hasInternalId() {
            return !internalId.isBlank();
        }

        public String displayNameWithoutModifier() {
            return stripModifierPrefix(displayName, modifier);
        }
    }
}
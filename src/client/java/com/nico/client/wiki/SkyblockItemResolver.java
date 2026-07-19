package com.nico.client.wiki;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

public final class SkyblockItemResolver {
    private SkyblockItemResolver() { }

    public static ItemIdentity resolveIdentity(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return new ItemIdentity("", "");
        }

        String internalId = readInternalId(stack);
        String modifier = readModifier(stack);

        String displayName = cleanDisplayName(
                stack.getHoverName().getString(),
                modifier
        );

        return new ItemIdentity(internalId, displayName);
    }

    private static String readModifier(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return "";
        }

        CompoundTag data = customData.copyTag();

        String directModifier = data.getString("modifier").orElse("").trim();
        if (!directModifier.isBlank()) {
            return directModifier;
        }

        return data.getCompound("ExtraAttributes")
                .flatMap(extra -> extra.getString("modifier"))
                .orElse("")
                .trim();
    }

    private static String readInternalId(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return "";
        }

        CompoundTag data = customData.copyTag();

        String directId = data.getString("id").orElse("").trim();
        if (!directId.isBlank()) {
            return directId;
        }

        return data.getCompound("ExtraAttributes")
                .flatMap(extra -> extra.getString("id"))
                .orElse("")
                .trim();
    }

    private static String readIdFromCustomDataRoot(Object rootTag) {
        Object root = unwrapOptional(rootTag);
        if (root == null) {
            return "";
        }

        // Current component representation: {id:"ASPECT_OF_THE_END", ...}
        String directId = readString(root, "id");
        if (!directId.isBlank()) {
            return directId;
        }

        // Legacy representation: {ExtraAttributes:{id:"ASPECT_OF_THE_END", ...}}
        Object extraAttributes = invokeKeyMethod(
                root,
                List.of("getCompound", "getCompoundOrEmpty"),
                "ExtraAttributes"
        );

        extraAttributes = unwrapOptional(extraAttributes);
        return extraAttributes == null ? "" : readString(extraAttributes, "id");
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
        } catch (ReflectiveOperationException ignored) {
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
                } catch (ReflectiveOperationException ignored) {
                    // Try the next mapping name.
                }
            }

            return customData;
        } catch (ReflectiveOperationException ignored) {
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
                } catch (ReflectiveOperationException ignored) {
                    // Try another overload or mapping name.
                }
            }
        }
        return null;
    }

    private static Object unwrapOptional(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private static String cleanDisplayName(String input, String modifier) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String cleaned = input
                .replaceAll("(?i)\\u00a7[0-9A-FK-ORX]", "")
                .replaceAll("[\\u278A-\\u2793\\u272A\\u2726\\u2605\\u2606]+", "")
                .replaceFirst("(?i)^\\s*\\[\\s*Lvl\\s+\\d+\\s*]\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();

        String reforgePrefix = formatModifier(modifier);
        if (!reforgePrefix.isBlank()) {
            cleaned = cleaned.replaceFirst(
                    "(?i)^" + java.util.regex.Pattern.quote(reforgePrefix) + "\\s+",
                    ""
            );
        }

        return cleaned.trim();
    }

    private static String formatModifier(String modifier) {
        if (modifier == null || modifier.isBlank()) {
            return "";
        }

        String normalized = modifier
                .trim()
                .toLowerCase(java.util.Locale.ROOT)
                .replaceFirst("_(sword|bow)$", "")
                .replace('_', ' ');

        StringBuilder result = new StringBuilder();

        for (String word : normalized.split("\\s+")) {
            if (word.isBlank()) {
                continue;
            }

            if (result.length() > 0) {
                result.append(' ');
            }

            result.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1));
        }

        return result.toString();
    }

    public record ItemIdentity(String internalId, String displayName) {
        public ItemIdentity {
            internalId = internalId == null ? "" : internalId.trim();
            displayName = displayName == null ? "" : displayName.trim();
        }

        public boolean hasInternalId() {
            return !internalId.isBlank();
        }
    }
}
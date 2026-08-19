package com.nico.client.utils.tradeprot.valuation;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.*;

final class NbtView {
    private NbtView() { }

    static Object compound(Object parent, String key) {
        return unwrap(invokeKey(parent, List.of("getCompound", "getCompoundOrEmpty"), key));
    }

    static String string(Object parent, String key) {
        Object value = unwrap(invokeKey(parent, List.of("getString", "getStringOr"), key));
        return value instanceof String string ? string.trim() : "";
    }

    static int integer(Object parent, String key) {
        Object value = unwrap(invokeKey(parent, List.of("getInt", "getIntOr"), key));
        return value instanceof Number number ? number.intValue() : 0;
    }

    static boolean booleanValue(Object parent, String key) {
        Object value = unwrap(invokeKey(parent, List.of("getBoolean", "getBooleanOr"), key));
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return false;
    }

    static Set<String> keys(Object compound) {
        if (compound == null) return Set.of();
        for (String methodName : List.of("getAllKeys", "keySet")) {
            try {
                Method method = compound.getClass().getMethod(methodName);
                Object result = unwrap(method.invoke(compound));
                if (result instanceof Collection<?> collection) {
                    Set<String> keys = new LinkedHashSet<>();
                    for (Object value : collection) if (value != null) keys.add(value.toString());
                    return keys;
                }
            } catch (ReflectiveOperationException ignored) { }
        }
        return Set.of();
    }

    static List<String> stringList(Object parent, String key) {
        Object value = raw(parent, key);
        if (value == null) return List.of();

        List<String> result = new ArrayList<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) addString(result, element);
            return List.copyOf(result);
        }
        if (value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) addString(result, Array.get(value, i));
            return List.copyOf(result);
        }

        Integer size = invokeInt(value, "size");
        if (size != null && size >= 0 && size <= 1024) {
            for (int i = 0; i < size; i++) addString(result, invokeIndex(value, i));
        }
        return List.copyOf(result);
    }

    static String scalarString(Object value) {
        value = unwrap(value);
        if (value == null) return "";
        if (value instanceof String string) return string.trim();
        if (value instanceof Number || value instanceof Boolean) return value.toString();

        for (String methodName : List.of("asString", "getAsString", "value")) {
            try {
                Method method = value.getClass().getMethod(methodName);
                Object result = unwrap(method.invoke(value));
                if (result instanceof String string) return stripQuotes(string.trim());
            } catch (ReflectiveOperationException ignored) { }
        }
        return stripQuotes(value.toString().trim());
    }

    static Object raw(Object parent, String key) {
        return unwrap(invokeKey(parent, List.of("get"), key));
    }

    private static void addString(List<String> result, Object value) {
        String string = scalarString(value);
        if (!string.isBlank()) result.add(string);
    }

    private static Object invokeKey(Object target, List<String> names, String key) {
        if (target == null) return null;
        for (String name : names) {
            for (Method method : target.getClass().getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() == 0
                        || method.getParameterTypes()[0] != String.class) continue;

                try {
                    if (method.getParameterCount() == 1) return method.invoke(target, key);
                    Class<?> second = method.getParameterTypes()[1];
                    if (second == String.class) return method.invoke(target, key, "");
                    if (second == int.class) return method.invoke(target, key, 0);
                    if (second == long.class) return method.invoke(target, key, 0L);
                    if (second == boolean.class) return method.invoke(target, key, false);
                } catch (ReflectiveOperationException ignored) { }
            }
        }
        return null;
    }

    private static Integer invokeInt(Object target, String methodName) {
        try {
            Object value = unwrap(target.getClass().getMethod(methodName).invoke(target));
            return value instanceof Number number ? number.intValue() : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object invokeIndex(Object target, int index) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().startsWith("get") || method.getParameterCount() != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter != int.class && parameter != Integer.class) continue;
            try {
                return unwrap(method.invoke(target, index));
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Object unwrap(Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    private static String stripQuotes(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }
}

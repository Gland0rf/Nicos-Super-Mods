package com.nico.client.party;

import com.nico.client.configuration.NsmConfigManager;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PartySafety {
    private static final long RECENT_JOIN_WINDOW_MILLIS = 15_000L;

    private static final Pattern USERNAME_AT_END = Pattern.compile("([A-Za-z0-9_]{1,16})$");
    private static final Pattern PARTY_TRANSFER_COMMAND =
            Pattern.compile("^(?:p|party)\\s+transfer\\s+([A-Za-z0-9_]{1,16})(?:\\s+.*)?$", Pattern.CASE_INSENSITIVE);

    private static final String PARTY_CHAT_PREFIX = "Party > ";
    private static final String WARNING_MESSAGE_PREFIX = "[NSM] Killed party transfer command triggered by: ";
    private static final String WARNING_MESSAGE_SUFFIX = ". Reason: Player joined party and immediately tries to take lead.";

    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final Map<String, Long> RECENT_JOINS = new HashMap<>();
    private static final Map<String, PartyChatTrigger> RECENT_CHAT_TRIGGERS = new HashMap<>();

    private PartySafety() { }

    public static synchronized void onSystemChat(Component component) {
        if (component == null || !enabled() || !isHypixel()) return;

        long now = System.currentTimeMillis();
        cleanupExpired(now);

        String text = stripFormatting(component.getString()).trim();
        if (text.isEmpty()) return;

        String joinedPlayer = extractJoinedPlayer(text);
        if (joinedPlayer != null) {
            String key = normalizePlayerName(joinedPlayer);
            RECENT_JOINS.put(key, now);
            RECENT_CHAT_TRIGGERS.remove(key);
            return;
        }

        PartyChatMessage partyMessage = parsePartyChat(text);
        if (partyMessage == null || !partyMessage.message().stripLeading().startsWith("!")) return;

        String key = normalizePlayerName(partyMessage.playerName());
        Long joinedAt = RECENT_JOINS.get(key);
        if (joinedAt == null || now - joinedAt > RECENT_JOIN_WINDOW_MILLIS) return;

        RECENT_CHAT_TRIGGERS.put(key, new PartyChatTrigger(now, partyMessage.message().strip()));
    }

    /**
     * @return true when the outgoing command should be cancelled
     */
    public static synchronized boolean interceptOutgoingCommand(String rawCommand) {
        if (rawCommand == null || !enabled() || !isHypixel()) return false;

        long now = System.currentTimeMillis();
        cleanupExpired(now);

        String command = rawCommand.strip();
        if (command.startsWith("/")) {
            command = command.substring(1).stripLeading();
        }

        Matcher transferMatcher = PARTY_TRANSFER_COMMAND.matcher(command);
        if (!transferMatcher.matches()) return false;

        // Commands typed directly into Minecraft's chat by the user are always allowed.
        if (wasManuallySubmittedFromChat()) return false;

        String targetName = transferMatcher.group(1);
        String key = normalizePlayerName(targetName);

        Long joinedAt = RECENT_JOINS.get(key);
        if (joinedAt == null || now - joinedAt > RECENT_JOIN_WINDOW_MILLIS) return false;

        PartyChatTrigger trigger = RECENT_CHAT_TRIGGERS.get(key);
        if (trigger == null || trigger.timeMillis() < joinedAt || now - trigger.timeMillis() > RECENT_JOIN_WINDOW_MILLIS) return false;

        String sourceMod = resolveSourceMod();
        showBlockedWarning(targetName, joinedAt, now, trigger.command(), sourceMod);

        if (prefillWarningEnabled()) openPrefilledPartyWarning(sourceMod);

        return true;
    }

    private static boolean enabled() {
        return NsmConfigManager.getConfig().other.partySafety.enabled;
    }

    private static boolean prefillWarningEnabled() {
        return NsmConfigManager.getConfig().other.partySafety.prefillPartyWarning;
    }

    private static void cleanupExpired(long now) {
        RECENT_JOINS.entrySet().removeIf(entry -> now - entry.getValue() > RECENT_JOIN_WINDOW_MILLIS);
        RECENT_CHAT_TRIGGERS.entrySet().removeIf(entry -> {
            Long joinedAt = RECENT_JOINS.get(entry.getKey());
            return joinedAt == null
                    || entry.getValue().timeMillis() < joinedAt
                    || now - entry.getValue().timeMillis() > RECENT_JOIN_WINDOW_MILLIS;
        });
    }

    private static String extractJoinedPlayer(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String joinPhrase = " joined the party.";

        int phraseIndex = lower.indexOf(joinPhrase);
        if (phraseIndex < 0) return null;

        String beforeJoin = text.substring(0, phraseIndex).trim();
        int arrowIndex = beforeJoin.lastIndexOf('>');
        if (arrowIndex > 0) {
            beforeJoin = beforeJoin.substring(arrowIndex + 1).trim();
        }

        beforeJoin = beforeJoin.replaceAll("\\[[^\\]]+\\]", " ").trim();
        Matcher usernameMatcher = USERNAME_AT_END.matcher(beforeJoin);
        if (usernameMatcher.find()) {
            return usernameMatcher.group(1);
        }

        return null;
    }

    private static PartyChatMessage parsePartyChat(String text) {
        if (!text.startsWith(PARTY_CHAT_PREFIX)) return null;

        int separator = text.indexOf(':', PARTY_CHAT_PREFIX.length());
        if (separator < 0) return null;

        String speaker = text.substring(PARTY_CHAT_PREFIX.length(), separator)
                .replaceAll("\\[[^\\]]+\\]", "")
                .trim();

        Matcher usernameMatcher = USERNAME_AT_END.matcher(speaker);
        if (!usernameMatcher.find()) return null;

        String message = text.substring(separator + 1).stripLeading();
        return new PartyChatMessage(usernameMatcher.group(1), message);
    }

    private static String stripFormatting(String text) {
        if (text == null) return "";
        return text.replaceAll("(?i)\\u00a7[0-9A-FK-OR]", "");
    }

    private static String normalizePlayerName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static boolean wasManuallySubmittedFromChat() {
        return STACK_WALKER.walk(frames -> frames.anyMatch(frame -> {
            String className = frame.getClassName();
            return className.equals("net.minecraft.client.gui.screens.ChatScreen")
                    || className.equals("net.minecraft.client.gui.screens.ChatScreen$");
        }));
    }

    private static String resolveSourceMod() {
        try {
            return STACK_WALKER.walk(frames -> frames
                    .map(StackWalker.StackFrame::getDeclaringClass)
                    .filter(PartySafety::isPossibleExternalModClass)
                    .map(PartySafety::findOwningMod)
                    .flatMap(Optional::stream)
                    .filter(container -> !isIgnoredMod(container))
                    .map(container -> container.getMetadata().getName())
                    .filter(name -> name != null && !name.isBlank())
                    .findFirst()
                    .orElse("unknown"));
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static boolean isPossibleExternalModClass(Class<?> clazz) {
        String name = clazz.getName();
        return !name.startsWith("com.nico")
                && !name.startsWith("com.mojang")
                && !name.startsWith("net.fabricmc")
                && !name.startsWith("org.spongepowered")
                && !name.startsWith("com.llamalad7.mixinextras")
                && !name.startsWith("io.netty")
                && !name.startsWith("java.")
                && !name.startsWith("javax.")
                && !name.startsWith("jdk.")
                && !name.startsWith("sun.")
                && !name.startsWith("kotlin.")
                && !name.startsWith("org.jetbrains.");
    }

    private static Optional<ModContainer> findOwningMod(Class<?> clazz) {
        String relativeClassPath = clazz.getName().replace('.', '/') + ".class";
        ModContainer found = null;

        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            boolean containsClass = false;
            try {
                for (Path root : container.getRootPaths()) {
                    if (Files.exists(root.resolve(relativeClassPath))) {
                        containsClass = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                continue;
            }

            if (!containsClass) continue;

            ModContainer topLevel = topLevelContainer(container);
            if (found == null) {
                found = topLevel;
                continue;
            }

            if (!found.getMetadata().getId().equals(topLevel.getMetadata().getId())) {
                return Optional.empty();
            }
        }

        return Optional.ofNullable(found);
    }

    private static ModContainer topLevelContainer(ModContainer container) {
        ModContainer current = container;
        Set<String> visited = new HashSet<>();

        while (visited.add(current.getMetadata().getId())) {
            Optional<ModContainer> parent = current.getContainingMod();
            if (parent.isEmpty()) break;
            current = parent.get();
        }

        return current;
    }

    private static boolean isIgnoredMod(ModContainer container) {
        String id = container.getMetadata().getId();
        return id.equals("nsm")
                || id.equals("minecraft")
                || id.equals("fabricloader")
                || id.equals("fabric-api")
                || id.startsWith("fabric-");
    }

    private static void showBlockedWarning(String targetName, long joinedAt, long now, String trigger, String sourceMod) {
        double joinedSecondsAgo = Math.max(0L, now - joinedAt) / 1000.0;
        String message = String.format(
                Locale.ROOT,
                "[NSM] Blocked automatic party transfer to %s (joined %.1fs ago, trigger: %s, source: %s)",
                targetName,
                joinedSecondsAgo,
                trigger,
                sourceMod
        );

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.gui.getChat().addClientSystemMessage(Component.literal(message)));
    }

    private static void openPrefilledPartyWarning(String sourceMod) {
        String warning = WARNING_MESSAGE_PREFIX + sourceMod + WARNING_MESSAGE_SUFFIX;
        String chatInput = "/pc" + warning;

        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            // Do not replace another open screen or anything the user is already typing.
            if (minecraft.screen != null) return;
            minecraft.setScreen(new ChatScreen(chatInput, true));
        });
    }

    private static boolean isHypixel() {
        Minecraft minecraft = Minecraft.getInstance();
        ServerData server = minecraft.getCurrentServer();
        if (server == null || server.ip == null) return false;

        String address = server.ip.toLowerCase(Locale.ROOT).trim();
        int colon = address.indexOf(':');
        if (colon >= 0) {
            address = address.substring(0, colon);
        }

        return address.equals("hypixel.net") || address.endsWith(".hypixel.net");
    }

    private record PartyChatMessage(String playerName, String message) { }

    private record PartyChatTrigger(long timeMillis, String command) { }
}

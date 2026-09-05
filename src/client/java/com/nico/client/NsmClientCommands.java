package com.nico.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.nico.client.bloodrush.BloodRoutes;
import com.nico.client.bloodrush.RouteCommands;
import com.nico.client.bloodrush.RouteContext;
import com.nico.client.bloodrush.RouteEditor;
import com.nico.client.configuration.NsmConfigManager;
import com.nico.client.dungeon.DungeonScanner;
import com.nico.client.dungeon.DungeonTeammateScanner;
import com.nico.client.memleak.MemLeakFeature;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class NsmClientCommands {

    private static RouteEditor routeEditor;

    private NsmClientCommands() { }

    public static void register() {
        routeEditor = BloodRoutes.initialize(new RouteContext());

        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> {
                    registerRoomsCommand(dispatcher);
                    registerConfigCommands(dispatcher);
                }
        );
    }

    private static void registerRoomsCommand(
            com.mojang.brigadier.CommandDispatcher<
                    net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
                    > dispatcher
    ) {
        dispatcher.register(
                ClientCommands.literal("nsmrooms")
                        .executes(context -> executeRoomsCommand())
        );
    }

    private static void registerConfigCommands(
            com.mojang.brigadier.CommandDispatcher<
                    net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
                    > dispatcher
    ) {
        dispatcher.register(
                ClientCommands.literal("nsmconfig")
                        .executes(context -> openConfigScreen())
        );

        dispatcher.register(
                ClientCommands.literal("nsm")
                        .executes(context -> openConfigScreen())
                        .then(RouteCommands.node(routeEditor))
                        .then(createMemoryNode("memory"))
                        .then(createMemoryNode("memleak"))
        );
    }

    private static int executeRoomsCommand() {
        try {
            printPlayerRooms();
            return 1;
        } catch (Throwable throwable) {
            throwable.printStackTrace();

            sendMessage(
                    Component.literal(
                            "§cNSM room command crashed. Check latest.log."
                    )
            );

            return 0;
        }
    }

    private static int openConfigScreen() {
        Minecraft minecraft = Minecraft.getInstance();

        minecraft.execute(() ->
                minecraft.setScreen(
                        NsmConfigManager.createScreen(minecraft.screen)
                )
        );

        return 1;
    }

    private static void printPlayerRooms() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        Set<String> teammateNames = getDungeonTeammateNames();

        sendMessage(
                Component.literal(
                        "§a--- Secret Stack Tracker Room Debug ---"
                )
        );

        sendMessage(
                Component.literal(
                        "§7Odin teammates found: §e" + teammateNames.size()
                )
        );

        for (Player player : minecraft.level.players()) {
            printPlayerRoom(player, teammateNames);
        }
    }

    private static void printPlayerRoom(
            Player player,
            Set<String> teammateNames
    ) {
        String playerName = player.getName().getString();

        if (!teammateNames.contains(playerName)) {
            return;
        }

        String roomName = DungeonScanner.getRoomNameForPlayer(player);

        int x = player.blockPosition().getX();
        int y = player.blockPosition().getY();
        int z = player.blockPosition().getZ();

        sendMessage(
                Component.literal(
                        "§e" + playerName
                                + " §7-> §b" + roomName
                                + " §8(" + x + ", " + y + ", " + z + ")"
                )
        );
    }

    public static Set<String> getDungeonTeammateNames() {
        Set<String> names = new HashSet<>();

        try {
            Set<String> teammateNames = DungeonTeammateScanner.getDungeonTeammateNames();
            for (String name : teammateNames) {
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

        return names;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> createMemoryNode(String literal) {
        return ClientCommands.literal(literal)
                .executes(context ->
                        sendMemLeakLines(MemLeakFeature.statusLines())
                )
                .then(
                        ClientCommands.literal("status")
                                .executes(context -> sendMemLeakLines(MemLeakFeature.statusLines()))
                )
                .then(
                        ClientCommands.literal("diagnose")
                                .executes(context -> sendMemLeakLines(MemLeakFeature.diagnosisLines()))
                )
                .then(
                        ClientCommands.literal("suspects")
                                .executes(context -> sendMemLeakLines(MemLeakFeature.diagnosisLines()))
                )
                .then(
                        ClientCommands.literal("mods")
                                .executes(context -> sendMemLeakLines(MemLeakFeature.modIndexLines()))
                )
                .then(
                        ClientCommands.literal("cleanup")
                                .executes(context -> sendMemLeakLines(MemLeakFeature.cleanupLines()))
                )
                .then(
                        ClientCommands.literal("reset")
                                .executes(context -> resetMemLeakMonitor())
                )
                .then(
                        ClientCommands.literal("report")
                                .executes(context -> exportMemLeakReport())
                )
                .then(
                        ClientCommands.literal("export")
                                .executes(context -> exportMemLeakReport())
                );
    }

    private static int sendMemLeakLines(Iterable<String> lines) {
        for (String line : lines) {
            sendMessage(Component.literal(line));
        }

        return 1;
    }

    private static int resetMemLeakMonitor() {
        if (!MemLeakFeature.reset()) {
            sendMessage(Component.literal("§c[NSM Memory Check] The detector is disabled."));

            return 0;
        }

        sendMessage(Component.literal("§a[NSM Memory Check] Monitoring baseline reset."));

        return 1;
    }

    private static int exportMemLeakReport() {
        try {
            Path report = MemLeakFeature.exportReport();

            if (report == null) {
                sendMessage(Component.literal("§c[NSM Memory Check] The detector is disabled."));

                return 0;
            }

            sendMessage(Component.literal("§a[NSM Memory Check] Report created."));
            sendMessage(Component.literal("§7Saved to: §f" + report.toAbsolutePath()));
            sendMemLeakLines(MemLeakFeature.statusLines());

            return 1;
        } catch (IOException exception) {
            exception.printStackTrace();

            sendMessage(Component.literal("§c[NSM Memory Check] Could not create the report: " + exception.getMessage()));
            return 0;
        }
    }

    public static RouteEditor getRouteEditor() {
        return routeEditor;
    }

    private static void sendMessage(Component message) {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(message);
        }
    }
}
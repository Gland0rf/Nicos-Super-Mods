package com.nico.client.bloodrush;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.BiFunction;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class RouteCommands {

    private RouteCommands() { }

    public static LiteralArgumentBuilder<FabricClientCommandSource> node(RouteEditor editor) {
        var route = literal("route")
                .requires(RouteCommands::isOwner)
                .executes(context -> showHelp(context.getSource()));

        route.then(recordNode(editor));

        route.then(literal("add")
                .executes(context -> reply(context.getSource(), editor.addPoint())));

        route.then(literal("etherwarp")
                .executes(context -> reply(context.getSource(), editor.addEtherwarp())));

        route.then(literal("ew")
                .executes(context -> reply(context.getSource(), editor.addEtherwarp())));

        route.then(literal("dungeonbreaker")
                .executes(context -> reply(context.getSource(), editor.addBreakerBlock())));

        route.then(literal("db")
                .executes(context -> reply(context.getSource(), editor.addBreakerBlock())));

        route.then(literal("undo")
                .executes(context -> reply(context.getSource(), editor.undo())));

        route.then(literal("remove-point")
                .then(argument("index", IntegerArgumentType.integer(1))
                        .executes(context -> reply(
                                context.getSource(),
                                editor.removePoint(IntegerArgumentType.getInteger(context, "index"))
                        ))));

        route.then(literal("save")
                .executes(context -> reply(context.getSource(), editor.save())));

        route.then(literal("cancel")
                .executes(context -> reply(context.getSource(), editor.cancel())));

        // Deletes one route line for the current entrance -> exit traversal.
        route.then(routeTargets("delete", (source, key) -> reply(source, editor.deleteRoute(key))));
        route.then(routeTargets("remove", (source, key) -> reply(source, editor.deleteRoute(key))));

        // Deletes all class routes only for the current entrance -> exit traversal.
        route.then(literal("clear-connection")
                .executes(context -> reply(context.getSource(), editor.clearConnection())));

        // Deletes every entrance/exit route saved for the current room.
        route.then(literal("reset-room")
                .executes(context -> reply(context.getSource(), editor.resetRoom())));
        route.then(literal("clear-room")
                .executes(context -> reply(context.getSource(), editor.resetRoom())));

        // Preview
        var preview = literal("preview");

        preview.then(previewTarget("healer", RouteKey.HEALER, editor));
        preview.then(previewTarget("mage", RouteKey.MAGE, editor));
        preview.then(previewTarget("archer", RouteKey.ARCHER, editor));

        preview.then(literal("from")
                .executes(context -> reply(
                        context.getSource(),
                        editor.setPreviewStart()
                )));

        preview.then(literal("to")
                .executes(context -> reply(
                        context.getSource(),
                        editor.previewTo()
                )));

        route.then(preview);

        route.then(literal("auto")
                .executes(context -> reply(context.getSource(), editor.auto())));

        route.then(literal("hide")
                .executes(context -> reply(context.getSource(), editor.hide())));

        route.then(literal("exit")
                .executes(context -> reply(context.getSource(), editor.setExitDoor())));

        route.then(literal("all")
                .executes(context -> reply(context.getSource(), editor.showAllRoomRoutes())));

        route.then(literal("copy")
                .then(argument(
                        "filename",
                        StringArgumentType.greedyString()
                ).executes(context -> reply(context.getSource(), editor.copyRoute(StringArgumentType.getString(context, "filename"))))));

        route.then(literal("paste")
                .executes(context -> reply(context.getSource(), editor.pasteRoute())));

        return route;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> routeTargets(
            String command,
            BiFunction<FabricClientCommandSource, RouteKey, Integer> action
    ) {
        var root = literal(command);

        root.then(literal("healer")
                .executes(context -> action.apply(context.getSource(), RouteKey.HEALER)));

        root.then(literal("mage")
                .executes(context -> action.apply(context.getSource(), RouteKey.MAGE)));

        root.then(literal("archer")
                .executes(context -> action.apply(context.getSource(), RouteKey.ARCHER)));

        return root;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> previewTarget(
            String name,
            RouteKey key,
            RouteEditor editor
    ) {
        var target = literal(name)
                .executes(context -> reply(
                        context.getSource(),
                        editor.preview(key)
                ));

        var start = literal("start")
                .executes(context -> reply(
                        context.getSource(),
                        editor.previewFrom(key, null)
                ));

        start.then(argument("index", IntegerArgumentType.integer(1))
                .executes(context -> reply(
                        context.getSource(),
                        editor.previewFrom(
                                key,
                                IntegerArgumentType.getInteger(context, "index")
                        )
                )));

        target.then(start);

        return target;
    }

    private static boolean isOwner(FabricClientCommandSource source) {
        return source.getPlayer()
                .getName()
                .getString()
                .equalsIgnoreCase("Nico8k");
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> recordNode(
            RouteEditor editor
    ) {
        var record = literal("record");

        record.then(recordTarget(
                "healer",
                RouteKey.HEALER,
                editor
        ));

        record.then(recordTarget(
                "mage",
                RouteKey.MAGE,
                editor
        ));

        record.then(recordTarget(
                "archer",
                RouteKey.ARCHER,
                editor
        ));

        return record;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> recordTarget(
            String name,
            RouteKey first,
            RouteEditor editor
    ) {
        var target = literal(name)
                .executes(context -> reply(
                        context.getSource(),
                        editor.start(List.of(first))
                ));

        for (RouteKey second : RouteKey.values()) {
            if (second == first) continue;

            target.then(literal(second.fileName())
                    .executes(context -> reply(
                            context.getSource(),
                            editor.start(List.of(first, second))
                    )));
        }

        return target;
    }

    private static int showHelp(FabricClientCommandSource source) {
        source.sendFeedback(Component.literal("§b§lNSM Blood Routes"));
        source.sendFeedback(Component.literal("§7/nsm route record <healer|mage|archer> §8- Start recording"));
        source.sendFeedback(Component.literal("§7/nsm route add §8- Add your current position"));
        source.sendFeedback(Component.literal("§7/nsm route etherwarp §8- Add an Etherwarp using the block you're looking at"));
        source.sendFeedback(Component.literal("§7/nsm route <dungeonbreaker/db> §8- Mark the block you're looking at for Dungeonbreaker"));
        source.sendFeedback(Component.literal("§7/nsm route undo §8- Remove the last point"));
        source.sendFeedback(Component.literal("§7/nsm route remove-point <number> §8- Remove a specific point"));
        source.sendFeedback(Component.literal("§7/nsm route save §8- Save the current route"));
        source.sendFeedback(Component.literal("§7/nsm route cancel §8- Cancel recording"));

        source.sendFeedback(Component.literal("§7/nsm route record <class> [class] §8- Record one route for one or two classes"));

        source.sendFeedback(Component.literal("§7/nsm route copy <filename> §8- Copy a saved route in this room"));

        source.sendFeedback(Component.literal("§7/nsm route paste §8- Paste the copied route into the current recording"));

        source.sendFeedback(Component.literal("§7/nsm route preview <class> §8- Show all routes for that class in this room"));
        source.sendFeedback(Component.literal("§7/nsm route preview <class> start §8- Show all routes for that class from the nearest door"));
        source.sendFeedback(Component.literal("§7/nsm route preview <class> start <index> §8- Show one route from the nearest door"));
        source.sendFeedback(Component.literal("§7/nsm route preview from §8- Set the nearest door as preview start"));
        source.sendFeedback(Component.literal("§7/nsm route preview to §8- Show routes from the selected start to the nearest door"));

        source.sendFeedback(Component.literal("§7/nsm route delete <healer|mage|archer> §8- Delete a route"));
        source.sendFeedback(Component.literal("§7/nsm route reset-room §8- Delete all routes for this room"));
        source.sendFeedback(Component.literal("§7/nsm route auto §8- Use your dungeon class automatically"));
        source.sendFeedback(Component.literal("§7/nsm route hide §8- Hide the route"));
        source.sendFeedback(Component.literal("§7/nsm route exit §8- Set the exit to the nearest door"));
        source.sendFeedback(Component.literal("§7/nsm route all §8- Show every saved route in this room"));
        return 1;
    }

    private static int reply(FabricClientCommandSource source, RouteEditor.Result result) {
        Component message = Component.literal("§b[NSM] §a" + result.message());

        if (result.success()) {
            source.sendFeedback(message);
            return 1;
        }

        source.sendError(Component.literal("§c[NSM] " + result.message()));
        return 0;
    }

}

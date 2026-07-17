package com.nico.client.hud;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public class HudMoveCommand {
    private HudMoveCommand() { }

    public static void register(HudLayoutManager layoutManager) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                    literal("nsm")
                            .then(literal("gui")
                                    .executes(context -> {
                                        Minecraft.getInstance().execute(() -> {
                                            Minecraft.getInstance().setScreen(
                                                    new HudMoveScreen(layoutManager)
                                            );
                                        });

                                        return Command.SINGLE_SUCCESS;
                                    })
                            )
                            .then(literal("hud")
                                    .executes(context -> {
                                        Minecraft.getInstance().execute(() -> {
                                            Minecraft.getInstance().setScreen(
                                                    new HudMoveScreen(layoutManager)
                                            );
                                        });

                                        return Command.SINGLE_SUCCESS;
                                    })
                            )
            );
        });
    }
}

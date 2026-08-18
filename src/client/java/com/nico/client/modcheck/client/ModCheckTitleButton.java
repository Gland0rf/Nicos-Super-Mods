package com.nico.client.modcheck.client;

import com.nico.client.modcheck.ModCheckRuntime;
import com.nico.client.modcheck.scan.ScanReport;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

public class ModCheckTitleButton {
    private ModCheckTitleButton() { }

    public static void register() {
        ScreenEvents.AFTER_INIT.register(
                (client, screen, scaledWidth, scaledHeight) -> {
                    if (!(screen instanceof TitleScreen)) return;

                    Button button = Button.builder(
                            Component.literal("NSM ModCheck"),
                            ignored ->{
                                ScanReport report = ModCheckRuntime.report();

                                if (report != null) {
                                    client.setScreen(
                                            new ModCheckWarningScreen(
                                                    screen,
                                                    report
                                            )
                                    );
                                }
                            }
                    ).bounds(
                            scaledWidth - 126,
                            6,
                            120,
                            20
                    ).build();

                    button.active = ModCheckRuntime.report() != null;

                    Screens.getWidgets(screen).add(button);
                }
        );
    }
}

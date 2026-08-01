package com.nico.client.watchConnection;

import com.nico.client.hud.HudElement;
import com.nico.client.hud.HudLayoutManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

import java.awt.Desktop;
import java.net.URI;

public class HeartRateFeature {
    /*
     * Register one Pulsoid API client for the mod, then replace this value
     * with its public client_id. Never put the client_secret in the JAR.
     */
    private static final String PULSOID_CLIENT_ID =
            "REPLACE_WITH_YOUR_PULSOID_CLIENT_ID";

    private static final long STALE_READING_MS = 30_000L;
    private static final HeartRateState STATE = new HeartRateState();

    private static HudLayoutManager layoutManager;
    private static HeartRateConfig config;
    private static PulsoidOAuthClient oauthClient;
    private static PulsoidWsClient pulsoidClient;

    private static boolean initialized;

    private HeartRateFeature() {
    }

    public static void initialize(HudLayoutManager manager) {
        if (initialized) {
            return;
        }

        initialized = true;
        layoutManager = manager;
        config = HeartRateConfig.load();
        oauthClient = new PulsoidOAuthClient(PULSOID_CLIENT_ID);

        pulsoidClient = new PulsoidWsClient(
                config.accessToken,
                STATE,
                HeartRateFeature::handleUnauthorizedToken
        );

        if (config.hasUsableAccessToken()) {
            pulsoidClient.connect();
        } else {
            config.clearAccessToken();
            config.save();
            beginAuthorization();
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pulsoidClient.shouldReconnect()) {
                pulsoidClient.reconnect();
            }
        });

        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(
                        "nicos_super_mods",
                        "heart_rate"
                ),
                HeartRateFeature::render
        );
    }

    private static void beginAuthorization() {
        oauthClient.authorize(new PulsoidOAuthClient.Listener() {
            @Override
            public void onStatus(String message) {
                STATE.setConnected(false);
                STATE.setStatus(message);
            }

            @Override
            public void onVerificationUri(URI uri) {
                System.out.println(
                        "[NSM HeartRate] Authorize Pulsoid here: " + uri
                );
                openBrowser(uri);
            }

            @Override
            public void onAuthorized(PulsoidOAuthClient.AccessToken token) {
                config.setAccessToken(
                        token.value(),
                        token.expiresInSeconds()
                );
                config.save();

                pulsoidClient.setAccessToken(token.value());
                STATE.setStatus("Pulsoid authorized");
                pulsoidClient.connect();
            }

            @Override
            public void onFailure(String message, Throwable throwable) {
                STATE.setConnected(false);
                STATE.setStatus(message);

                System.err.println("[NSM HeartRate] " + message);
                if (throwable != null) {
                    throwable.printStackTrace();
                }
            }
        });
    }

    private static void handleUnauthorizedToken() {
        config.clearAccessToken();
        config.save();
        pulsoidClient.setAccessToken("");
        beginAuthorization();
    }

    private static void openBrowser(URI uri) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(uri);
            } else {
                STATE.setStatus("Authorize Pulsoid using the URL in the log");
            }
        } catch (Exception exception) {
            STATE.setStatus("Authorize Pulsoid using the URL in the log");
            System.err.println(
                    "[NSM HeartRate] Could not open browser: "
                            + exception.getMessage()
            );
        }
    }

    private static void render(
            GuiGraphics graphics,
            DeltaTracker deltaTracker
    ) {
        if (!config.enabled) {
            return;
        }

        HudElement element = layoutManager.get(
                HudLayoutManager.HEART_RATE
        );

        if (element == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;

        String text;
        int colour;

        if (!STATE.isConnected()) {
            text = "❤ " + STATE.getStatus();
            colour = 0xFFAAAAAA;
        } else if (STATE.getBpm() <= 0) {
            text = "❤ Waiting for BPM...";
            colour = 0xFFFFFF55;
        } else if (System.currentTimeMillis() - STATE.getLastUpdate()
                > STALE_READING_MS) {
            text = "❤ No recent BPM";
            colour = 0xFFFFFF55;
        } else {
            int bpm = STATE.getBpm();
            text = "❤ " + bpm + " BPM";
            colour = getColour(bpm);
        }

        element.setMeasuredSize(
                font.width(text),
                font.lineHeight
        );

        if (!element.hasBeenSeen()) {
            element.setSeen(true);
            layoutManager.save();
        }

        float scale = (float) element.getScale();

        Matrix3x2fStack matrices = graphics.pose();
        matrices.pushMatrix();

        try {
            matrices.translate(
                    element.getX(),
                    element.getY()
            );
            matrices.scale(scale, scale);

            graphics.drawString(
                    font,
                    text,
                    0,
                    0,
                    colour,
                    true
            );
        } finally {
            matrices.popMatrix();
        }
    }

    private static int getColour(int bpm) {
        if (bpm >= 120) {
            return 0xFFAA0000;
        }

        return 0xFFFF5555;
    }
}

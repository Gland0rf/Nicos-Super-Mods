package com.nico.client.watchConnection;

import com.nico.client.configuration.NsmConfig;
import com.nico.client.configuration.category.CategoryOther;
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

public final class HeartRateFeature {

    /*
     * Replace this with the public client_id registered for your mod.
     * Never include the Pulsoid client_secret in the mod JAR.
     */
    private static final String PULSOID_CLIENT_ID =
            "REPLACE_WITH_YOUR_PULSOID_CLIENT_ID";

    private static final HeartRateState STATE = new HeartRateState();

    private static HudLayoutManager layoutManager;
    private static HeartRateConfig oauthConfig;
    private static PulsoidOAuthClient oauthClient;
    private static PulsoidWsClient pulsoidClient;

    private static boolean initialized;
    private static boolean featureActive;

    private HeartRateFeature() {
    }

    public static void initialize(HudLayoutManager manager) {
        if (initialized) {
            return;
        }

        initialized = true;
        layoutManager = manager;

        oauthConfig = HeartRateConfig.load();
        oauthClient = new PulsoidOAuthClient(PULSOID_CLIENT_ID);

        pulsoidClient = new PulsoidWsClient(
                oauthConfig.accessToken,
                STATE,
                HeartRateFeature::handleUnauthorizedToken
        );

        featureActive = settings().enabled;

        if (featureActive) {
            connectOrAuthorize();
        } else {
            STATE.setConnected(false);
            STATE.setStatus("Heart Rate HUD disabled");
        }

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean enabled = settings().enabled;

            /*
             * Detect when the MoulConfig enable option has changed.
             */
            if (enabled != featureActive) {
                featureActive = enabled;

                if (enabled) {
                    connectOrAuthorize();
                } else {
                    stopConnection();
                }
            }

            if (enabled && pulsoidClient.shouldReconnect()) {
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

    /**
     * Called by the MoulConfig reconnect button.
     */
    public static void reconnect() {
        runOnClientThread(() -> {
            if (!initialized || !settings().enabled) {
                return;
            }

            oauthClient.cancelAuthorization();
            pulsoidClient.disconnect();
            connectOrAuthorize();
        });
    }

    /**
     * Called by the MoulConfig reset-login button.
     */
    public static void resetAuthorization() {
        runOnClientThread(() -> {
            if (!initialized) {
                return;
            }

            oauthClient.cancelAuthorization();
            pulsoidClient.disconnect();
            pulsoidClient.setAccessToken("");

            oauthConfig.clearAccessToken();
            oauthConfig.save();

            STATE.setConnected(false);
            STATE.setStatus("Pulsoid login reset");

            if (settings().enabled) {
                beginAuthorization();
            }
        });
    }

    private static CategoryOther.HeartRateHud settings() {
        return NsmConfig.INSTANCE.other.heartRateHud;
    }

    private static void connectOrAuthorize() {
        if (!settings().enabled) {
            return;
        }

        if (oauthConfig.hasUsableAccessToken()) {
            pulsoidClient.setAccessToken(oauthConfig.accessToken);
            pulsoidClient.connect();
            return;
        }

        oauthConfig.clearAccessToken();
        oauthConfig.save();

        pulsoidClient.setAccessToken("");
        beginAuthorization();
    }

    private static void beginAuthorization() {
        if (!settings().enabled || oauthClient.isAuthorizing()) {
            return;
        }

        oauthClient.authorize(new PulsoidOAuthClient.Listener() {
            @Override
            public void onStatus(String message) {
                if (!settings().enabled) {
                    return;
                }

                STATE.setConnected(false);
                STATE.setStatus(message);
            }

            @Override
            public void onVerificationUri(URI uri) {
                if (!settings().enabled) {
                    return;
                }

                System.out.println(
                        "[HeartRateHUD] Authorize Pulsoid here: " + uri
                );

                openBrowser(uri);
            }

            @Override
            public void onAuthorized(
                    PulsoidOAuthClient.AccessToken token
            ) {
                oauthConfig.setAccessToken(
                        token.value(),
                        token.expiresInSeconds()
                );
                oauthConfig.save();

                pulsoidClient.setAccessToken(token.value());

                if (!settings().enabled) {
                    STATE.setConnected(false);
                    STATE.setStatus("Heart Rate HUD disabled");
                    return;
                }

                STATE.setStatus("Pulsoid authorized");
                pulsoidClient.connect();
            }

            @Override
            public void onFailure(
                    String message,
                    Throwable throwable
            ) {
                if (settings().enabled) {
                    STATE.setConnected(false);
                    STATE.setStatus(message);
                }

                System.err.println("[HeartRateHUD] " + message);

                if (throwable != null) {
                    throwable.printStackTrace();
                }
            }
        });
    }

    private static void handleUnauthorizedToken() {
        runOnClientThread(() -> {
            oauthConfig.clearAccessToken();
            oauthConfig.save();

            pulsoidClient.setAccessToken("");

            if (settings().enabled) {
                beginAuthorization();
            }
        });
    }

    private static void stopConnection() {
        oauthClient.cancelAuthorization();
        pulsoidClient.disconnect();

        STATE.setConnected(false);
        STATE.setStatus("Heart Rate HUD disabled");
    }

    private static void runOnClientThread(Runnable runnable) {
        Minecraft.getInstance().execute(runnable);
    }

    private static void openBrowser(URI uri) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(uri);
            } else {
                STATE.setStatus(
                        "Authorize Pulsoid using the URL in the log"
                );
            }
        } catch (Exception exception) {
            STATE.setStatus(
                    "Authorize Pulsoid using the URL in the log"
            );

            System.err.println(
                    "[HeartRateHUD] Could not open browser: "
                            + exception.getMessage()
            );
        }
    }

    private static void render(
            GuiGraphics graphics,
            DeltaTracker deltaTracker
    ) {
        CategoryOther.HeartRateHud settings = settings();

        if (!settings.enabled) {
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

        long staleTimeoutMs = Math.max(
                1L,
                30L
        ) * 1_000L;

        if (!STATE.isConnected()) {

            text = "❤ " + STATE.getStatus();
            colour = 0xFFAAAAAA;
        } else if (STATE.getBpm() <= 0) {

            text = "❤ Waiting for BPM...";
            colour = 0xFFFFFF55;
        } else if (
                System.currentTimeMillis() - STATE.getLastUpdate()
                        > staleTimeoutMs
        ) {

            text = "❤ No recent BPM";
            colour = 0xFFFFFF55;
        } else {
            int bpm = STATE.getBpm();

            text = "❤ " + bpm + " BPM";
            colour = getColour(
                    bpm,
                    settings.highBpmThreshold
            );
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

    private static int getColour(
            int bpm,
            int highBpmThreshold
    ) {
        if (bpm >= Math.max(1, highBpmThreshold)) {
            return 0xFFAA0000;
        }

        return 0xFFFF5555;
    }
}
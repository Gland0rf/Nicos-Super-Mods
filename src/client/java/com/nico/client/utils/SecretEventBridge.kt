package com.nico.client.utils

import com.nico.client.SecretStackTrackerClient
import com.nico.client.goldor.GoldorTerminalHighlighter
import com.nico.client.stacking.SecretStackingDetector
import com.odtheking.odin.events.RenderEvent
import com.odtheking.odin.events.RoomEnterEvent
import com.odtheking.odin.events.core.*
import com.odtheking.odin.utils.noControlCodes
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import java.lang.IO.println
import java.util.regex.Pattern

object SecretEventBridge {

    private var subscribed = false

    private val secretRegex = Pattern.compile("(\\d+)/(\\d+) Secrets")

    private const val BRIDGE_PRIORITY = 10_000;

    init {
        /*on<SecretPickupEvent.Interact>(priority = BRIDGE_PRIORITY) {
            safe("SecretPickupEvent.Interact") {
                println("[NSM] SecretPickupEvent.Interact fired")
                SecretStackTrackerClient.onOdinSecretPickup(blockPos)
            }
        }

        on<SecretPickupEvent.Item>(priority = BRIDGE_PRIORITY) {
            safe("SecretPickupEvent.Item") {
                println("[NSM] SecretPickupEvent.Item fired")
                SecretStackTrackerClient.onOdinSecretPickup(entity.blockPosition())
            }
        }

        on<SecretPickupEvent.Bat>(priority = BRIDGE_PRIORITY) {
            safe("SecretPickupEvent.Bat") {
                println("[NSM] SecretPickupEvent.Bat fired")
                SecretStackTrackerClient.onOdinSecretPickup(
                    BlockPos.containing(packet.x, packet.y, packet.z)
                )
            }
        }*/

        onReceive<ClientboundSystemChatPacket> {
            safe("ClientboundSystemChatPacket") {
                val clean = content().string.noControlCodes

                GoldorTerminalHighlighter.onChatMessage(clean);

                val matcher = secretRegex.matcher(clean)

                if (!matcher.find()) return@onReceive

                val foundSecrets = Integer.parseInt(matcher.group(1))
                val totalSecrets = Integer.parseInt(matcher.group(2))

                SecretStackingDetector.onRoomSecretsPacket(foundSecrets, totalSecrets)
            }
        }

        on<RoomEnterEvent> {
            safe("RoomEnterEvent") {
                SecretStackingDetector.onRoomChanged()
                GoldorTerminalHighlighter.onRoomChanged();
            }
        }

        on<RenderEvent.Extract> {
            safe("RenderEvent.Extract") {
                GoldorTerminalHighlighter.render(this);
            }
        }
    }

    fun init() {
        if (subscribed) {
            println("[NSM] SecretEventBridge already subscribed; skipping")
            return
        }

        subscribed = true;
        EventBus.subscribe(this)

        println("[NSM] SecretEventBridge subscribed")
    }

    private inline fun safe(name: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            println("[NSM] ERROR in $name")
            t.printStackTrace()
        }
    }

}
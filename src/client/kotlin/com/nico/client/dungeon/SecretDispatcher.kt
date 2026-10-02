package com.nico.client.dungeon

import com.nico.client.secretTimer.SecretRoomTimerClient
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.ClientRecipeBook
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.phys.Vec3

object SecretDispatcher {
    private var initialized = false
    private var lastGameMessage = ""
    private var lastGameMessageOverlay = false
    private var lastGameMessageAtNanos = 0L

    private const val BAT_DAMAGE_EVIDENCE_WINDOW_MS = 2500L
    private const val BAT_ABILITY_MATCH_WINDOW_MS = 900L

    private val recentBatDamage = mutableMapOf<Int, RecentBatDamage>()
    private var lastSelfAbilityDamageAtMs = 0L
    private var pendingBatSlainAtMs = 0L

    private val selfAbilityDamageRegex = Regex(
        """^Your .+ hit \d+ (?:enemy|enemies) for .+ damage\.?$""",
        RegexOption.IGNORE_CASE
    )

    private data class RecentBatDamage(
        val pos: BlockPos,
        val observedAtMs: Long,
        val bySelf: Boolean
    )

    private val dungeonItemDrops = arrayOf(
        "Health Potion VIII Splash Potion",
        "Healing Potion 8 Splash Potion",
        "Healing Potion VIII Splash Potion",
        "Healing VIII Splash Potion",
        "Healing 8 Splash Potion",
        "Decoy",
        "Inflatable Jerry",
        "Spirit Leap",
        "Trap",
        "Training Weights",
        "Defuse Kit",
        "Dungeon Chest Key",
        "Treasure Talisman",
        "Revive Stone",
        "Architect's First Draft",
        "Secret Dye",
        "Candycomb"
    )

    private val secretCounterRegexes = listOf(
        Regex("""(\d+)\s*/\s*(\d+)\s+Secrets?""", RegexOption.IGNORE_CASE),
        Regex("""Secrets?\s*:?\s*(\d+)\s*/\s*(\d+)""", RegexOption.IGNORE_CASE)
    )

    @JvmStatic
    fun init() {
        if (initialized) return

        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            handleGameMessage(message.string, overlay)
            true
        }

        UseBlockCallback.EVENT.register { player, level, hand, hitResult ->
            handleBlockUse(player, level, hand, hitResult.blockPos)
            InteractionResult.PASS
        }

        AttackEntityCallback.EVENT.register { _, _, hand, entity, _ ->
            if (hand != InteractionHand.OFF_HAND && entity is Bat && isDungeonClearContext()) {
                noteSelfBatDamage(entity)
            }
            InteractionResult.PASS
        }

        ClientEntityEvents.ENTITY_UNLOAD.register { entity, _ ->
            if (entity is ItemEntity) {
                handlePotentialItemUnload(entity)
            }
        }

        initialized = true
        println("[NSM] SecretDispatcher Fabric compatibility hooks registered")
    }

    @JvmStatic
    fun onReceive(packet: Packet<*>) {
        try{
            when (packet) {
                is ClientboundTakeItemEntityPacket ->
                    handleTakeItem(packet)

                is ClientboundDamageEventPacket ->
                    handleDamageEvent(packet)

                is ClientboundSoundPacket ->
                    handleSound(packet)

                is ClientboundSystemChatPacket ->
                    handleSystemChat(packet)

                is ClientboundSetActionBarTextPacket ->
                    handleActionBar(packet)
            }
        } catch (throwable: Throwable) {
            System.err.println("[NSM] SecretDispatcher failed while handling ${packet.javaClass.simpleName}: ${throwable.message}")
            throwable.printStackTrace()
        }
    }

    @JvmStatic
    fun onSend(packet: Packet<*>) {
        if (packet is ServerboundUseItemOnPacket) {
            handleUseItemOn(packet)
        }
    }

    private fun handleTakeItem(packet: ClientboundTakeItemEntityPacket) {
        if (!isDungeonClearContext()) return


        val client = Minecraft.getInstance()
        val player = client.player ?: return
        if (packet.playerId != player.id) return
        val entity = client.level?.getEntity(packet.itemId) as? ItemEntity ?: return

        if (!isDungeonItemDrop(entity.item.hoverName.string)) return
        if (entity.distanceTo(player) > 8) return

        dispatchItemSecret(entity.blockPosition())
    }

    private fun handleDamageEvent(packet: ClientboundDamageEventPacket) {
        if (!isDungeonClearContext()) return

        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val level = client.level ?: return
        val bat = level.getEntity(packet.entityId()) as? Bat ?: return
        val bySelf = packet.sourceCauseId() == player.id || packet.sourceDirectId() == player.id
        val now = System.currentTimeMillis()

        pruneBatDamageEvidence(now)
        recentBatDamage[bat.id] = RecentBatDamage(
            bat.blockPosition().immutable(),
            now,
            bySelf
        )
    }

    private fun noteSelfBatDamage(bat: Bat) {
        val now = System.currentTimeMillis()
        pruneBatDamageEvidence(now)
        recentBatDamage[bat.id] = RecentBatDamage(
            bat.blockPosition().immutable(),
            now,
            true
        )
    }

    private fun handleBatCombatMessage(message: String) {
        val now = System.currentTimeMillis()
        pruneBatDamageEvidence(now)

        if (message.startsWith("A Bat has been slain.", ignoreCase = true)) {
            val directEvidence = recentBatDamage.entries
                .filter { now - it.value.observedAtMs <= BAT_DAMAGE_EVIDENCE_WINDOW_MS }
                .maxByOrNull { it.value.observedAtMs }

            if (directEvidence != null) {
                recentBatDamage.remove(directEvidence.key)

                if (directEvidence.value.bySelf) {
                    pendingBatSlainAtMs = 0L
                    SecretRoomTimerClient.onPotentialCombatSecretPickup(directEvidence.value.pos)
                    println("[NSM] Bat secret attributed from direct damage evidence")
                    return
                }
            }

            if (now - lastSelfAbilityDamageAtMs <= BAT_ABILITY_MATCH_WINDOW_MS) {
                pendingBatSlainAtMs = 0L
                SecretRoomTimerClient.onPotentialCombatSecretPickup(null)
                println("[NSM] Bat secret attributed from recent self ability damage")
                return
            }

            // Hypixel may send the bat-death line before its generic
            // "Your <ability> hit ..." line (Implosion does this, for example).
            pendingBatSlainAtMs = now
            return
        }

        if (!selfAbilityDamageRegex.matches(message)) return

        lastSelfAbilityDamageAtMs = now

        if (pendingBatSlainAtMs > 0L
            && now - pendingBatSlainAtMs <= BAT_ABILITY_MATCH_WINDOW_MS) {
            pendingBatSlainAtMs = 0L
            SecretRoomTimerClient.onPotentialCombatSecretPickup(null)
            println("[NSM] Bat secret attributed from self ability damage fallback")
        }
    }

    private fun pruneBatDamageEvidence(now: Long) {
        recentBatDamage.entries.removeIf {
            now - it.value.observedAtMs > BAT_DAMAGE_EVIDENCE_WINDOW_MS
        }

        if (pendingBatSlainAtMs > 0L
            && now - pendingBatSlainAtMs > BAT_ABILITY_MATCH_WINDOW_MS) {
            pendingBatSlainAtMs = 0L
        }
    }

    private fun handleSound(packet: ClientboundSoundPacket) {
        // Kept only as a compatibility no-op. Bat secrets are now attributed from the
        // local attack callback and confirmed by the room secret counter, which avoids
        // crediting a teammate's bat just because its death sound was audible.
    }

    private fun handleUseItemOn(packet: ServerboundUseItemOnPacket) {
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val level = client.level ?: return
        handleBlockUse(player, level, packet.hand, packet.hitResult.blockPos)
    }

    private fun handleBlockUse(player: Player, level: Level, hand: InteractionHand, pos: BlockPos) {
        if (!isDungeonClearContext()) return;
        if (hand == InteractionHand.OFF_HAND) return

        val blockState = level.getBlockState(pos)

        if (blockState.block is SkullBlock) {
            val target = Vec3(
                pos.x.toDouble(),
                pos.y.toDouble(),
                pos.z.toDouble()
            )

            if (player.eyePosition.distanceToSqr(target) > 20.25) return
        }

        if (DungeonSecretClassifier.isSecret(level, blockState, pos)) {
            if (blockState.`is`(Blocks.CHEST) || blockState.`is`(Blocks.TRAPPED_CHEST)) {
                dispatchChestSecret(pos)
            }else if (blockState.block is SkullBlock) {
                dispatchPotentialBlockSecret(pos)
            } else {
                dispatchSecret(pos)
            }
        }
    }

    private fun handleSystemChat(packet: ClientboundSystemChatPacket) {
        handleGameMessage(packet.content.string, packet.overlay())
    }

    private fun handleActionBar(packet: ClientboundSetActionBarTextPacket) {
        handleGameMessage(packet.text.string, true)
    }

    private fun handleGameMessage(raw: String, overlay: Boolean) {
        val clean = cleanText(raw)
        val now = System.nanoTime()

        // The same message can arrive through both the vanilla packet fallback and the
        // Fabric event path on a normal Fabric client.
        if (clean == lastGameMessage
            && overlay == lastGameMessageOverlay
            && now - lastGameMessageAtNanos < 100_000_000L) {
            return
        }

        lastGameMessage = clean
        lastGameMessageOverlay = overlay
        lastGameMessageAtNanos = now

        if (handleSecretCounter(clean)) return
        if (overlay) return

        handleBatCombatMessage(clean)

        if (clean.contains("That chest is locked!")) {
            SecretRoomTimerClient.onLockedChestMessage()
        }

        SecretRoomTimerClient.onChatMessage(clean)
    }

    private fun handleSecretCounter(text: String): Boolean {
        val match = secretCounterRegexes.firstNotNullOfOrNull { it.find(text) } ?: return false
        val found = match.groupValues[1].toIntOrNull() ?: return false
        val total = match.groupValues[2].toIntOrNull() ?: return false

        SecretRoomTimerClient.onRoomSecretsPacket(found, total)
        return true
    }

    private fun cleanText(text: String): String =
        text.replace(Regex("§[0-9A-FK-OR]", RegexOption.IGNORE_CASE), "").trim()

    private fun handlePotentialItemUnload(entity: ItemEntity) {
        if (!isDungeonClearContext()) return

        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val level = client.level ?: return

        if (!isDungeonItemDrop(entity.item.hoverName.string)) return

        val selfDistance = entity.distanceTo(player)
        if (selfDistance > 3.0) return

        for (other in level.players()) {
            if (other === player) continue
            if (entity.distanceTo(other) + 0.25 < selfDistance) return
        }

        SecretRoomTimerClient.onPotentialItemSecretPickup(entity.blockPosition())
    }

    private fun isDungeonClearContext(): Boolean {
        val client = Minecraft.getInstance()
        val player = client.player ?: return false
        if (client.level == null) return false

        if (DungeonState.inClear) return true

        // Fallback for clients whose tab/scoreboard rendering changes prevent
        // LocationUtils from being updated by packet mixins.
        return try {
            DungeonScanner.isInDungeon(player)
        } catch (_: Throwable) {
            false
        }
    }

    private fun dispatchSecret(pos: BlockPos) {
        SecretRoomTimerClient.onSecretPickup(pos)
    }

    private fun dispatchItemSecret(pos: BlockPos) {
        SecretRoomTimerClient.onItemSecretPickup(pos)
    }

    private fun dispatchPotentialBlockSecret(pos: BlockPos) {
        SecretRoomTimerClient.onPotentialBlockSecretPickup(pos)
    }

    private fun dispatchChestSecret(pos: BlockPos) {
        SecretRoomTimerClient.onChestSecretPickup(pos)
    }

    private fun isDungeonItemDrop(name: String): Boolean =
        dungeonItemDrops.any { name.contains(it, ignoreCase = true) }
}
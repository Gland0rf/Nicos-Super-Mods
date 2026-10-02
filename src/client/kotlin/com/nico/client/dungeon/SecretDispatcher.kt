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
    private const val BAT_UNLOAD_ABILITY_MATCH_WINDOW_MS = 3000L
    private const val BAT_ATTRIBUTION_DEDUPE_MS = 1500L
    private const val BAT_UNLOAD_MAX_DISTANCE = 20.0f
    private var lastBatAttributionRoom: String? = null

    private val recentBatDamage = mutableMapOf<Int, RecentBatDamage>()
    private var lastSelfAbilityDamageAtMs = 0L
    private var pendingBatSlainAtMs = 0L
    private var pendingBatAbilityMatchWindowMs = BAT_ABILITY_MATCH_WINDOW_MS
    private var lastBatAttributionAtMs = 0L

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
            when (entity) {
                is ItemEntity -> handlePotentialItemUnload(entity)
                is Bat -> handlePotentialBatUnload(entity)
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
                    dispatchBatSecret(directEvidence.value.pos, "direct damage evidence")
                    return
                }
            }

            if (now - lastSelfAbilityDamageAtMs <= BAT_ABILITY_MATCH_WINDOW_MS) {
                dispatchBatSecret(null, "recent self ability damage")
                return
            }

            // Hypixel may send the bat-death line before its generic
            // "Your <ability> hit ..." line (Implosion does this, for example).
            pendingBatSlainAtMs = now
            pendingBatAbilityMatchWindowMs = BAT_ABILITY_MATCH_WINDOW_MS
            return
        }

        if (!selfAbilityDamageRegex.matches(message)) return

        lastSelfAbilityDamageAtMs = now

        if (pendingBatSlainAtMs > 0L
            && now - pendingBatSlainAtMs <= pendingBatAbilityMatchWindowMs) {
            dispatchBatSecret(null, "self ability damage fallback")
        }
    }

    private fun pruneBatDamageEvidence(now: Long) {
        recentBatDamage.entries.removeIf {
            now - it.value.observedAtMs > BAT_DAMAGE_EVIDENCE_WINDOW_MS
        }

        if (pendingBatSlainAtMs > 0L
            && now - pendingBatSlainAtMs > pendingBatAbilityMatchWindowMs) {
            pendingBatSlainAtMs = 0L
            pendingBatAbilityMatchWindowMs = BAT_ABILITY_MATCH_WINDOW_MS
        }
    }

    /**
    +     * Compatibility fallback for clients where another mod filters, compacts or delays the
    +     * "A Bat has been slain" chat line. A bat disappearing very close to the player is
    +     * treated as the same death candidate that the chat path would have produced. Final
    +     * credit still goes through the room secret counter in SecretRoomTimerClient.
    +     */
    private fun handlePotentialBatUnload(bat: Bat) {
        if (!isDungeonClearContext()) return

        val client = Minecraft.getInstance()
        val player = client.player ?: return
        if (bat.distanceTo(player) > BAT_UNLOAD_MAX_DISTANCE) return

        val now = System.currentTimeMillis()
        pruneBatDamageEvidence(now)

        val directEvidence = recentBatDamage.remove(bat.id)
        if (directEvidence != null
                && now - directEvidence.observedAtMs <= BAT_DAMAGE_EVIDENCE_WINDOW_MS
                && directEvidence.bySelf) {
                dispatchBatSecret(directEvidence.pos, "bat entity unload with direct self damage")
            return
        }

        val abilityAgeMs = if (lastSelfAbilityDamageAtMs > 0L) {
            now - lastSelfAbilityDamageAtMs
        } else {
             Long.MAX_VALUE
        }

        if (abilityAgeMs <= BAT_UNLOAD_ABILITY_MATCH_WINDOW_MS) {
            dispatchBatSecret(bat.blockPosition().immutable(),
                "bat entity unload after self ability damage (${abilityAgeMs}ms)")
            return
        }

        pendingBatSlainAtMs = now
        pendingBatAbilityMatchWindowMs = BAT_UNLOAD_ABILITY_MATCH_WINDOW_MS
        val ageText = if (abilityAgeMs == Long.MAX_VALUE) "none" else "${abilityAgeMs}ms"
        println("[NSM] Bat death candidate observed from entity unload (last self ability: $ageText ago)")
    }

    private fun dispatchBatSecret(pos: BlockPos?, reason: String) {
        val now = System.currentTimeMillis()
        pendingBatSlainAtMs = 0L
        pendingBatAbilityMatchWindowMs = BAT_ABILITY_MATCH_WINDOW_MS

        val client = Minecraft.getInstance()
        val player = client.player
        val roomName = if (player != null) {
            try {
                DungeonScanner.getRoomNameForPlayer(player)
                    .takeUnless { it.isBlank() || it == "Unknown" }
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }

        val sameRoom = roomName == lastBatAttributionRoom
        if (sameRoom && now - lastBatAttributionAtMs <= BAT_ATTRIBUTION_DEDUPE_MS) {
            println("[NSM] Ignored duplicate bat attribution in ${roomName ?: "unknown room"} ($reason)")
            return
        }

        lastBatAttributionAtMs = now
        lastBatAttributionRoom = roomName
        SecretRoomTimerClient.onPotentialCombatSecretPickup(pos)
        println("[NSM] Bat secret attributed from $reason")
    }

    @JvmStatic
    fun clearTransientState() {
        recentBatDamage.clear()
        lastSelfAbilityDamageAtMs = 0L
        pendingBatSlainAtMs = 0L
        pendingBatAbilityMatchWindowMs = BAT_ABILITY_MATCH_WINDOW_MS
        lastBatAttributionAtMs = 0L
        lastBatAttributionRoom = null
        lastGameMessage = ""
        lastGameMessageOverlay = false
        lastGameMessageAtNanos = 0L
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
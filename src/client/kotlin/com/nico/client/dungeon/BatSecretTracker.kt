package com.nico.client.dungeon

import com.nico.client.secretTimer.SecretRoomTimerClient
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.world.entity.ambient.Bat

/**
+ * Correlates bat deaths with evidence that the local player caused the kill.
+ *
+ * Hypixel can deliver the relevant damage chat, entity removal, and damage packets in
+ * different orders. Modded clients can widen those gaps further, so attribution deliberately
+ * combines several short-lived signals and lets the room secret counter provide final confirmation.
+ */
internal object BatSecretTracker {
    private const val DAMAGE_EVIDENCE_WINDOW_MS = 2_500L
    private const val ABILITY_MATCH_WINDOW_MS = 900L
    private const val UNLOAD_ABILITY_MATCH_WINDOW_MS = 3_000L
    private const val ATTRIBUTION_DEDUPE_MS = 1_500L
    private const val UNLOAD_MAX_DISTANCE = 20.0f

    private val selfAbilityDamageRegex = Regex(
        """^Your .+ hit \d+ (?:enemy|enemies) for .+ damage\.?$""",
        RegexOption.IGNORE_CASE
    )

    private val recentDamage = mutableMapOf<Int, RecentBatDamage>()

    private var lastSelfAbilityDamageAtMs = 0L
    private var pendingBatDeathAtMs = 0L
    private var pendingAbilityMatchWindowMs = ABILITY_MATCH_WINDOW_MS
    private var lastAttributionAtMs = 0L
    private var lastAttributionRoom: String? = null

    fun onDamageEvent(packet: ClientboundDamageEventPacket) {
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val level = client.level ?: return
        val bat = level.getEntity(packet.entityId()) as? Bat ?: return
        val now = System.currentTimeMillis()

        pruneExpiredEvidence(now)
        recentDamage[bat.id] = RecentBatDamage(
            position = bat.blockPosition().immutable(),
            observedAtMs = now,
            causedBySelf = packet.sourceCauseId() == player.id || packet.sourceDirectId() == player.id
        )
    }

    fun onSelfAttack(bat: Bat) {
        val now = System.currentTimeMillis()
        pruneExpiredEvidence(now)
        recentDamage[bat.id] = RecentBatDamage(
            position = bat.blockPosition().immutable(),
            observedAtMs = now,
            causedBySelf = true
        )
    }

    fun onCombatMessage(message: String) {
        val now = System.currentTimeMillis()
        pruneExpiredEvidence(now)

        if (message.startsWith("A Bat has been slain.", ignoreCase = true)) {
            handleBatSlainMessage(now)
            return
        }

        if (!selfAbilityDamageRegex.matches(message)) return
        lastSelfAbilityDamageAtMs = now
        if (pendingBatDeathAtMs > 0L && now - pendingBatDeathAtMs <= pendingAbilityMatchWindowMs) {
            dispatchSecret(position = null, reason = "self ability damage fallback")
        }
    }

    /**
     * Fallback for clients where another mod filters, compacts, or delays the bat-death chat line.
     * An actual nearby bat disappearing is stronger evidence than chat alone, so it gets a wider
     * ability-correlation window without weakening normal chat-only attribution.
     */
    fun onEntityUnload(bat: Bat) {
        val player = Minecraft.getInstance().player ?: return
        if (bat.distanceTo(player) > UNLOAD_MAX_DISTANCE) return

        val now = System.currentTimeMillis()
        pruneExpiredEvidence(now)

        val directEvidence = recentDamage.remove(bat.id)
        if (directEvidence != null
            && now - directEvidence.observedAtMs <= DAMAGE_EVIDENCE_WINDOW_MS
            && directEvidence.causedBySelf
        ) {
            dispatchSecret(directEvidence.position, "bat entity unload with direct self damage")
            return
        }

        val abilityAgeMs = lastSelfAbilityDamageAtMs
            .takeIf { it > 0L }
            ?.let { now - it }
            ?: Long.MAX_VALUE

        if (abilityAgeMs <= UNLOAD_ABILITY_MATCH_WINDOW_MS) {
            dispatchSecret(
                bat.blockPosition().immutable(),
                "bat entity unload after self ability damage (${abilityAgeMs}ms)"
            )
            return
        }

        pendingBatDeathAtMs = now
        pendingAbilityMatchWindowMs = UNLOAD_ABILITY_MATCH_WINDOW_MS

        val ageText = abilityAgeMs.takeUnless { it == Long.MAX_VALUE }?.let { "${it}ms" } ?: "none"
        println("[NSM] Bat death candidate observed from entity unload (last self ability: $ageText ago)")
    }

    fun clear() {
        recentDamage.clear()
        lastSelfAbilityDamageAtMs = 0L
        pendingBatDeathAtMs = 0L
        pendingAbilityMatchWindowMs = ABILITY_MATCH_WINDOW_MS
        lastAttributionAtMs = 0L
        lastAttributionRoom = null
    }

    private fun handleBatSlainMessage(now: Long) {
        val directEvidence = recentDamage.entries
            .filter { now - it.value.observedAtMs <= DAMAGE_EVIDENCE_WINDOW_MS }
            .maxByOrNull { it.value.observedAtMs }

        if (directEvidence != null) {
            recentDamage.remove(directEvidence.key)
            if (directEvidence.value.causedBySelf) {
                dispatchSecret(directEvidence.value.position, "direct damage evidence")
                return
            }
        }

        if (now - lastSelfAbilityDamageAtMs <= ABILITY_MATCH_WINDOW_MS) {
            dispatchSecret(position = null, reason = "recent self ability damage")
            return
        }

        // Hypixel may emit the bat-death line before the generic "Your <ability> hit ..." line.
        pendingBatDeathAtMs = now
        pendingAbilityMatchWindowMs = ABILITY_MATCH_WINDOW_MS
    }

    private fun pruneExpiredEvidence(now: Long) {
        recentDamage.entries.removeIf { now - it.value.observedAtMs > DAMAGE_EVIDENCE_WINDOW_MS }

        if (pendingBatDeathAtMs > 0L && now - pendingBatDeathAtMs > pendingAbilityMatchWindowMs) {
            pendingBatDeathAtMs = 0L
            pendingAbilityMatchWindowMs = ABILITY_MATCH_WINDOW_MS
        }
    }

    private fun dispatchSecret(position: BlockPos?, reason: String) {
        val now = System.currentTimeMillis()
        pendingBatDeathAtMs = 0L
        pendingAbilityMatchWindowMs = ABILITY_MATCH_WINDOW_MS

        val roomName = currentRoomName()
        if (roomName == lastAttributionRoom && now - lastAttributionAtMs <= ATTRIBUTION_DEDUPE_MS) {
            println("[NSM] Ignored duplicate bat attribution in ${roomName ?: "unknown room"} ($reason)")
            return
        }

        lastAttributionAtMs = now
        lastAttributionRoom = roomName
        SecretRoomTimerClient.onSecretPickup(position)
        println("[NSM] Bat secret attributed from $reason")
    }

    private fun currentRoomName(): String? {
        val player = Minecraft.getInstance().player ?: return null
        return try {
            DungeonScanner.getRoomNameForPlayer(player)
                .takeUnless { it.isBlank() || it == "Unknown" }
        } catch (_: Throwable) {
            null
        }
    }

    private data class RecentBatDamage(
        val position: BlockPos,
        val observedAtMs: Long,
        val causedBySelf: Boolean
    )
}
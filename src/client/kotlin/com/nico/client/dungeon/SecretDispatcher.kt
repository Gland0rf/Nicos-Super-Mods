package com.nico.client.dungeon

import com.nico.client.secretTimer.SecretRoomTimerClient
import com.nico.client.stacking.SecretStackingDetector
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.phys.Vec3

object SecretDispatcher {
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

    private val secretCounterRegex =
        Regex("""(\d+)/(\d+) Secrets""")

    @JvmStatic
    fun onReceive(packet: Packet<*>) {
        when (packet) {
            is ClientboundTakeItemEntityPacket ->
                handleTakeItem(packet)

            is ClientboundRemoveEntitiesPacket ->
                handleRemoveEntities(packet)

            is ClientboundSoundPacket ->
                handleSound(packet)

            is ClientboundSystemChatPacket ->
                handleSystemChat(packet)
        }
    }

    @JvmStatic
    fun onSend(packet: Packet<*>) {
        if (packet is ServerboundUseItemOnPacket) {
            handleUseItemOn(packet)
        }
    }

    private fun handleTakeItem(packet: ClientboundTakeItemEntityPacket) {
        if (!DungeonState.inClear) return


        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val entity = client.level?.getEntity(packet.itemId) as? ItemEntity ?: return

        if (!isDungeonItemDrop(entity.item.hoverName.string)) return
        if (entity.distanceTo(player) > 8) return

        dispatchItemSecret(entity.blockPosition())
    }

    private fun handleRemoveEntities(packet: ClientboundRemoveEntitiesPacket) {
        if (!DungeonState.inClear) return

        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val level = client.level ?: return

        packet.entityIds.forEach { id ->
            val entity = level.getEntity(id) as? ItemEntity ?: return@forEach

            if (!isDungeonItemDrop(entity.item.hoverName.string)) return@forEach
            if (entity.distanceTo(player) > 8) return@forEach

            dispatchItemSecret(entity.blockPosition())
        }
    }

    private fun handleSound(packet: ClientboundSoundPacket) {
        if (!DungeonState.inClear) return

        val sound = packet.sound.value()

        val isBatSound =
            sound == SoundEvents.BAT_HURT ||
            sound == SoundEvents.BAT_DEATH ||
            sound.location
                .toString()
                .contains("bat", ignoreCase = true)

        if (!isBatSound) return
        if (packet.volume > 0.3f) return

        dispatchSecret(BlockPos.containing(packet.x, packet.y, packet.z))
    }

    private fun handleUseItemOn(packet: ServerboundUseItemOnPacket) {
        if (!DungeonState.inClear) return
        if (packet.hand == InteractionHand.OFF_HAND) return

        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val level = client.level ?: return

        val pos = packet.hitResult.blockPos
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
            } else {
                dispatchSecret(pos)
            }
        }
    }

    private fun handleSystemChat(packet: ClientboundSystemChatPacket) {
        val clean = packet.content.string
            .replace(Regex("§[0-9A-FK-OR]", RegexOption.IGNORE_CASE), "")

        secretCounterRegex.find(clean)?.let { match ->
            val found = match.groupValues[1].toInt()
            val total = match.groupValues[2].toInt()

            SecretStackingDetector.onRoomSecretsPacket(found, total)
            SecretRoomTimerClient.onRoomSecretsPacket(found, total)

            return
        }

        if (packet.overlay()) return

        if (clean.contains("That chest is locked!")) {
            SecretRoomTimerClient.onLockedChestMessage()
        }

        SecretRoomTimerClient.onChatMessage(clean)
    }

    private fun dispatchSecret(pos: BlockPos) {
        SecretStackingDetector.onSecretPickup(pos)
        SecretRoomTimerClient.onSecretPickup(pos)
    }

    private fun dispatchItemSecret(pos: BlockPos) {
        SecretStackingDetector.onSecretPickup(pos)
        SecretRoomTimerClient.onItemSecretPickup(pos)
    }

    private fun dispatchChestSecret(pos: BlockPos) {
        SecretStackingDetector.onSecretPickup(pos)
        SecretRoomTimerClient.onChestSecretPickup(pos)
    }

    private fun isDungeonItemDrop(name: String): Boolean =
        dungeonItemDrops.any { name.contains(it, ignoreCase = true) }
}
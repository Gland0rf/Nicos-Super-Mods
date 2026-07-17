package com.nico.client

import com.nico.client.stacking.SecretStackingDetector
import com.odtheking.odin.events.core.EventBus
import com.odtheking.odin.events.core.onReceive
import com.odtheking.odin.events.core.onSend
import com.odtheking.odin.utils.noControlCodes
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.phys.Vec3

object SecretDispatcher {
    private var registered = false

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

    fun init() {
        System.out.println("[SST-Fallback] SecretDispatcher init() called")

        if (registered) {
            System.out.println("[SST-Fallback] SecretDispatcher already registered")
            return
        }

        registered = true

        System.out.println("[SST-Fallback] Registering fallback packet handlers")

        EventBus.subscribe(this);

        onReceive<ClientboundTakeItemEntityPacket> {
            val client = Minecraft.getInstance()
            val player = client.player ?: return@onReceive
            val entity = client.level?.getEntity(itemId) as? ItemEntity

            System.out.println("[SST-Fallback] TakeItem itemId=$itemId entity=$entity inClear=${DungeonUtils.inClear}")

            if (entity == null) return@onReceive

            val name = entity.item.hoverName.string.noControlCodes
            val distance = entity.distanceTo(player)

            System.out.println("[SST-Fallback] itemName='$name' distance=$distance")

            val isDungeonDrop = dungeonItemDrops.any { drop ->
                name.contains(drop, ignoreCase = true)
            }

            if (!DungeonUtils.inClear) return@onReceive
            if (!isDungeonDrop) return@onReceive
            if (distance > 8) return@onReceive // loosened from Odin's 6

            SecretStackingDetector.onOdinSecretPickup(entity.blockPosition())
        }

        onReceive<ClientboundRemoveEntitiesPacket> {
            val client = Minecraft.getInstance()
            val player = client.player ?: return@onReceive

            if (!DungeonUtils.inClear) return@onReceive

            entityIds.forEach { id ->
                val entity = client.level?.getEntity(id) as? ItemEntity ?: return@forEach

                val name = entity.item.hoverName.string.noControlCodes
                val distance = entity.distanceTo(player)

                System.out.println("[SST-Fallback] RemoveEntity itemName='$name' distance=$distance")

                val isDungeonDrop = dungeonItemDrops.any { drop ->
                    name.contains(drop, ignoreCase = true)
                }

                if (isDungeonDrop && distance <= 8) {
                    SecretStackingDetector.onOdinSecretPickup(entity.blockPosition())
                }
            }
        }

        onReceive<ClientboundSoundPacket> {
            val soundValue = sound.value()

            if (!DungeonUtils.inClear) return@onReceive

            val isBatSound =
                soundValue == SoundEvents.BAT_HURT ||
                        soundValue == SoundEvents.BAT_DEATH ||
                        soundValue.location.toString().contains("bat", ignoreCase = true)

            if (!isBatSound) return@onReceive

            System.out.println("[SST-Fallback] Bat sound=$soundValue volume=$volume")

            // Odin requires volume == 0.1f.
            // Lunar may not preserve that exactly, so loosen it.
            if (volume <= 0.3f) {
                SecretStackingDetector.onOdinSecretPickup(
                    net.minecraft.core.BlockPos.containing(x, y, z)
                )
            }
        }

        onSend<ServerboundUseItemOnPacket> {
            val client = Minecraft.getInstance()
            val player = client.player ?: return@onSend
            val level = client.level ?: return@onSend

            if (!DungeonUtils.inDungeons) return@onSend
            if (hand == InteractionHand.OFF_HAND) return@onSend

            val pos = hitResult.blockPos
            val blockState = level.getBlockState(pos)

            System.out.println("[SST-Fallback] UseItemOn pos=$pos state=$blockState")

            if (blockState.block is SkullBlock) {
                val distance = player.eyePosition.distanceToSqr(Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()))
                if (distance > 20.25) return@onSend
            }

            if (DungeonUtils.isSecret(blockState, pos)) {
                SecretStackingDetector.onOdinSecretPickup(pos)
            }
        }
    }
}
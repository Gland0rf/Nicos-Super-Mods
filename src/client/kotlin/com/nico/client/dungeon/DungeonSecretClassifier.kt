package com.nico.client.dungeon

import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.SkullBlock
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.level.block.state.BlockState

object DungeonSecretClassifier {
    private const val WITHER_ESSENCE_ID =
        "e0f3e929-869e-3dca-9504-54c666ee6f23"

    private const val REDSTONE_KEY_ID =
        "fed95410-aba1-39df-9b95-1d4f361eb66e"

    fun isSecret(level: Level, state: BlockState, pos: BlockPos): Boolean {
        if (
            state.`is`(Blocks.CHEST) ||
            state.`is`(Blocks.TRAPPED_CHEST) ||
            state.`is`(Blocks.LEVER)
        ) {
            return true
        }

        if (state.block !is SkullBlock) {
            return false
        }

        val skull = level.getBlockEntity(pos) as? SkullBlockEntity ?: return false
        val id = skull.ownerProfile
            ?.partialProfile()
            ?.id
            ?.toString()
            ?: return false

        return id == WITHER_ESSENCE_ID || id == REDSTONE_KEY_ID
    }
}
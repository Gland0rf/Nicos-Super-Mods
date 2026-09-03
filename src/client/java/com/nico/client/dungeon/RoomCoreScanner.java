package com.nico.client.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class RoomCoreScanner {

    /*
     * Room core hashing logic adapted from Odin.
     * BSD-3-Clause license:
     * see third_party/odin.
     */

    public Result scan(Level level, DungeonGrid.Tile tile) {
        int x = tile.centerX();
        int z = tile.centerZ();

        BlockPos chunkCheck = new BlockPos(x, 69, z);

        if (!level.hasChunkAt(chunkCheck)) return null;

        StringBuilder builder = new StringBuilder(1024);

        boolean foundHighest = false;
        int highestBlock = 0;
        int bedrock = 0;

        for (int y = 140; y >= 12; y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            Block block = state.getBlock();

            if (!foundHighest) {
                if (!state.isAir() && block != Blocks.GOLD_BLOCK) {
                    foundHighest = true;
                    highestBlock = y;
                } else {
                    builder.append('0');
                }
            }

            if (!foundHighest) continue;

            if (state.isAir() && bedrock >= 2 && y < 69) {
                for (int i = 0; i < y - 11; i++) {
                    builder.append('0');
                }

                break;
            }

            if (block == Blocks.BEDROCK) {
                bedrock++;
            } else {
                bedrock = 0;

                if (block == Blocks.OAK_PLANKS || block == Blocks.TRAPPED_CHEST || block == Blocks.CHEST) {
                    continue;
                }
            }

            builder.append(block);
        }

        return new Result(builder.toString().hashCode(), highestBlock);
    }

    public record Result(int core, int highestBlock) { }
}

package com.nico.client.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public final class DungeonDoorScanner {

    private static final int DOOR_Y = 69;

    /*
     * Door probe logic adapted from Odin.
     * BSD-3-Clause license:
     * see third_party/odin.
     */

    public boolean hasClosedWitherDoor(Level level, DungeonRoom room) {
        for (DungeonGrid.Tile tile : room.tiles()) {
            for (DungeonGrid.Direction direction : DungeonGrid.Direction.values()) {
                DungeonGrid.Tile neighbor = tile.offset(direction);
                if (!neighbor.isValid()) continue;

                // Boundary inside one multi-tile room.
                if (room.contains(neighbor)) continue;

                if (isClosedWitherDoorBetween(level, tile, neighbor)) return true;
            }
        }

        return false;
    }

    public boolean roomsConnected(Level level, DungeonRoom first, DungeonRoom second) {
        for (DungeonGrid.Tile tile : first.tiles()) {
            for (DungeonGrid.Direction direction : DungeonGrid.Direction.values()) {
                DungeonGrid.Tile neighbor = tile.offset(direction);
                if (!second.contains(neighbor)) continue;

                if (isDoorwayBetween(level, tile, neighbor)) return true;
            }
        }

        return false;
    }

    public boolean isClosedWitherDoorBetween(Level level, DungeonGrid.Tile first, DungeonGrid.Tile second) {
        BlockPos pos = getDoorPosition(first, second);

        if (pos == null || !level.hasChunkAt(pos)) return false;

        return level.getBlockState(pos).is(Blocks.COAL_BLOCK);
    }

    public boolean isDoorwayBetween(Level level, DungeonGrid.Tile first, DungeonGrid.Tile second) {
        BlockPos door = getDoorPosition(first, second);

        if (door == null || !level.hasChunkAt(door)) return false;

        int x = door.getX();
        int z = door.getZ();

        // If there are blocks above the doorway, reject it.
        for (int y = 86; y <= 160; y++) {
            if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) return false;
        }

        // If there is a floor, then it's a doorway.
        return !level.getBlockState(new BlockPos(x, 68, z)).isAir();
    }

    private BlockPos getDoorPosition(DungeonGrid.Tile first, DungeonGrid.Tile second) {
        int dx = Math.abs(first.x() - second.x());
        int dz = Math.abs(first.z() - second.z());

        if (dx + dz != 1) return null;

        int x = (first.centerX() + second.centerX()) / 2;
        int z = (first.centerZ() + second.centerZ()) / 2;

        return new BlockPos(x, DOOR_Y, z);
    }

}

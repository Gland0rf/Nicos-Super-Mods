package com.nico.client.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DungeonDoorScanner {

    private static final int DOOR_Y = 69;

    /*
     * Door probe logic adapted from Odin.
     * BSD-3-Clause license:
     * see third_party/odin.
     */

    public List<Door> getDoorsForRoom(Level level, DungeonLayoutScanner.Layout layout, DungeonRoom room) {
        Map<BlockPos, Door> doors = new LinkedHashMap<>();

        for (DungeonGrid.Tile tile : room.tiles()) {
            for (DungeonGrid.Direction direction : DungeonGrid.Direction.values()) {
                DungeonGrid.Tile neighbor = tile.offset(direction);
                if (!neighbor.isValid()) continue;

                // Separator inside one multi-tile room, not a door.
                if (room.contains(neighbor)) continue;

                BlockPos pos = getDoorPosition(tile, neighbor);
                if (pos == null || !level.hasChunkAt(pos)) continue;

                BlockState state = level.getBlockState(pos);
                boolean specialDoor = state.is(Blocks.COAL_BLOCK) || state.is(Blocks.RED_TERRACOTTA);
                if (!specialDoor && !isDoorwayBetween(level, tile, neighbor)) continue;

                DungeonRoom otherRoom = layout.roomAt(neighbor);
                doors.putIfAbsent(pos, new Door(pos, room, otherRoom));
            }
        }

        return List.copyOf(doors.values());
    }

    public Door findConnectingDoor(Level level, DungeonLayoutScanner.Layout layout, DungeonRoom first, DungeonRoom second) {
        if (first == null || second == null) return null;

        for (Door door : getDoorsForRoom(level, layout, first)) {
            if (second.equals(door.otherRoom())) {
                return door;
            }
        }

        return null;
    }

    public Type getType(Level level, Door door) {
        if (door == null || !level.hasChunkAt(door.pos())) {
            return Type.NORMAL;
        }

        BlockState state = level.getBlockState(door.pos());
        if (state.is(Blocks.COAL_BLOCK)) return Type.WITHER;
        if (state.is(Blocks.RED_TERRACOTTA)) return Type.BLOOD;
        return Type.NORMAL;
    }

    public boolean isClosedWitherDoor(Level level, Door door) {
        return door != null
                && level.hasChunkAt(door.pos())
                && level.getBlockState(door.pos()).is(Blocks.COAL_BLOCK);
    }

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

                if (isDoorwayBetween(level, tile, neighbor) || isSpecialDoorBetween(level, tile, neighbor)) return true;
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

    private boolean isSpecialDoorBetween(Level level, DungeonGrid.Tile first, DungeonGrid.Tile second) {
        BlockPos pos = getDoorPosition(first, second);
        if (pos == null || !level.hasChunkAt(pos)) return false;

        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.COAL_BLOCK) || state.is(Blocks.RED_TERRACOTTA);
    }

    public enum Type {
        NORMAL,
        WITHER,
        BLOOD
    }

    public record Door(BlockPos pos, DungeonRoom room, DungeonRoom otherRoom) { }

}

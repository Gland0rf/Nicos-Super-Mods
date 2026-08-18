package com.nico.client.dungeon;

import net.minecraft.world.entity.player.Player;

public final class DungeonGrid {

    public static final int GRID_SIZE = 6;
    public static final int ROOM_STEP = 32;
    public static final int FIRST_ROOM_CENTER = -185;
    public static final int HALF_ROOM = 16;

    private static final int GRID_START = FIRST_ROOM_CENTER - HALF_ROOM; // -201

    private DungeonGrid() { }

    public static Tile fromPlayer(Player player) {
        return fromWorld(player.getBlockX(), player.getBlockZ());
    }

    public static Tile fromWorld(int blockX, int blockZ) {
        int tileX = (blockX - GRID_START) >> 5;
        int tileZ = (blockZ - GRID_START) >> 5;

        Tile tile = new Tile(tileX, tileZ);
        return tile.isValid() ? tile : null;
    }

    public enum Direction {
        EAST(1, 0),
        WEST(-1, 0),
        SOUTH(0, 1),
        NORTH(0, -1);

        private final int dx;
        private final int dz;

        Direction(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        public int dx() { return dx; }
        public int dz() { return dz; }
    }

    public record Tile(int x, int z) {
        public boolean isValid() {
            return x >= 0
                    && x < GRID_SIZE
                    && z >= 0
                    && z < GRID_SIZE;
        }

        public int centerX() {
            return FIRST_ROOM_CENTER + x * ROOM_STEP;
        }

        public int centerZ() {
            return FIRST_ROOM_CENTER + z * ROOM_STEP;
        }

        public Tile offset(Direction direction) {
            return new Tile(
                    x + direction.dx(),
                    z + direction.dz()
            );
        }
    }

}

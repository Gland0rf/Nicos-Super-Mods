package com.nico.client.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves the same room-local origin/rotation used by dungeon waypoints.
 *
 * The blue terracotta marker sits 15 blocks diagonally from one room component
 * center at the room's top layer. Fairy has no marker, so it uses the standard
 * northwest fallback.
 */
public final class DungeonRoomGeometry {
    private static final int MARKER_OFFSET = 15;

    private Level currentLevel;
    private final Map<DungeonRoom, Orientation> orientationCache = new HashMap<>();

    public synchronized Orientation getOrientation(Level level, DungeonRoom room) {
        if (level == null || room == null) return null;

        if (currentLevel != level) {
            currentLevel = level;
            orientationCache.clear();
        }

        Orientation cached = orientationCache.get(room);
        if (cached != null) return cached;

        Orientation orientation = scanOrientation(level, room);
        if (orientation != null) {
            orientationCache.put(room, orientation);
        }

        return orientation;
    }

    private Orientation scanOrientation(Level level, DungeonRoom room) {
        DungeonGrid.Tile firstTile = room.tiles()
                .stream()
                .min(Comparator.comparingInt(DungeonGrid.Tile::z)
                        .thenComparing(DungeonGrid.Tile::x))
                .orElse(null);

        if (firstTile == null) return null;

        int roomHeight = getTopLayerOfRoom(level, firstTile);
        if (roomHeight == 0) return null;

        // Fairy has no blue terracotta marker.
        if (room.type() == DungeonRoomData.Type.FAIRY) {
            return new Orientation(
                    new BlockPos(
                            firstTile.centerX() - MARKER_OFFSET,
                            roomHeight,
                            firstTile.centerZ() - MARKER_OFFSET
                    ),
                    2
            );
        }

        for (Marker marker : Marker.values()) {
            for (DungeonGrid.Tile tile : room.tiles()) {
                BlockPos markerPos = new BlockPos(
                        tile.centerX() + marker.dx,
                        roomHeight,
                        tile.centerZ() + marker.dz
                );

                if (!level.hasChunkAt(markerPos)) continue;
                if (!level.getBlockState(markerPos).is(Blocks.BLUE_TERRACOTTA)) continue;

                if (room.tiles().size() > 1 && !isIsolatedMarker(level, markerPos)) {
                    continue;
                }

                return new Orientation(markerPos, marker.quarterTurns);
            }
        }

        return null;
    }

    private static int getTopLayerOfRoom(Level level, DungeonGrid.Tile tile) {
        int x = tile.centerX();
        int z = tile.centerZ();
        BlockPos chunkCheck = new BlockPos(x, 69, z);

        if (!level.hasChunkAt(chunkCheck)) return 0;

        for (int y = 160; y >= 12; y--) {
            Block block = level.getBlockState(new BlockPos(x, y, z)).getBlock();
            if (block == Blocks.AIR) continue;

            return block == Blocks.GOLD_BLOCK ? y - 1 : y;
        }

        return 0;
    }

    private static boolean isIsolatedMarker(Level level, BlockPos markerPos) {
        BlockPos[] neighbors = {
                markerPos.offset(1, 0, 0),
                markerPos.offset(-1, 0, 0),
                markerPos.offset(0, 0, 1),
                markerPos.offset(0, 0, -1)
        };

        for (BlockPos neighbor : neighbors) {
            Block block = level.getBlockState(neighbor).getBlock();
            if (block != Blocks.AIR && block != Blocks.BLUE_TERRACOTTA) {
                return false;
            }
        }

        return true;
    }

    /**
     * quarterTurns uses the bloodrush convention:
     * NORTH=0, EAST=1, SOUTH=2, WEST=3.
     */
    private enum Marker {
        NORTH(+MARKER_OFFSET, +MARKER_OFFSET, 0),
        EAST(-MARKER_OFFSET, +MARKER_OFFSET, 1),
        SOUTH(-MARKER_OFFSET, -MARKER_OFFSET, 2),
        WEST(+MARKER_OFFSET, -MARKER_OFFSET, 3);

        private final int dx;
        private final int dz;
        private final int quarterTurns;

        Marker(int dx, int dz, int quarterTurns) {
            this.dx = dx;
            this.dz = dz;
            this.quarterTurns = quarterTurns;
        }
    }

    public record Orientation(BlockPos pivot, int quarterTurns) { }
}

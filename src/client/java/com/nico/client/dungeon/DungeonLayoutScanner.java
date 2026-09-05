package com.nico.client.dungeon;

import net.minecraft.world.level.Level;

import java.util.*;

public final class DungeonLayoutScanner {

    private final DungeonRoomDatabase database = DungeonRoomDatabase.getInstance();

    private final RoomCoreScanner coreScanner = new RoomCoreScanner();

    private Level currentLevel;

    private final Map<DungeonGrid.Tile, DungeonRoomData> dataByTile = new HashMap<>();

    public synchronized void clearTransientState() {
        currentLevel = null;
        dataByTile.clear();
    }

    public synchronized Layout scan(Level level) {
        if (currentLevel != level) {
            currentLevel = level;
            dataByTile.clear();
        }

        scanLoadedTiles(level);

        Map<DungeonGrid.Tile, DungeonRoom> roomByTile = new LinkedHashMap<>();
        List<DungeonRoom> rooms = new ArrayList<>();
        Set<DungeonGrid.Tile> visited = new HashSet<>();

         /*
         * Build connected components instead of grouping only by RoomData.
         * The same room template can occur more than once in a dungeon; those
         * instances must stay separate even though their core hashes resolve
         * to the same database entry.
         */
        for (Map.Entry<DungeonGrid.Tile, DungeonRoomData> entry : dataByTile.entrySet()) {
            DungeonGrid.Tile start = entry.getKey();
            if (!visited.add(start)) continue;

            DungeonRoomData data = entry.getValue();
            Set<DungeonGrid.Tile> component = new LinkedHashSet<>();
            ArrayDeque<DungeonGrid.Tile> queue = new ArrayDeque<>();
            queue.add(start);

            while (!queue.isEmpty()) {
                DungeonGrid.Tile tile = queue.removeFirst();
                component.add(tile);

                for (DungeonGrid.Direction direction : DungeonGrid.Direction.values()) {
                    DungeonGrid.Tile neighbor = tile.offset(direction);
                    if (!neighbor.isValid() || visited.contains(neighbor)) continue;
                    if (!data.equals(dataByTile.get(neighbor))) continue;

                    visited.add(neighbor);
                    queue.addLast(neighbor);
                }
            }

            DungeonRoom room = new DungeonRoom(data, component);
            rooms.add(room);

            for (DungeonGrid.Tile tile : component) {
                roomByTile.put(tile, room);
            }
        }

        return new Layout(Map.copyOf(roomByTile), List.copyOf(rooms));
    }

    private void scanLoadedTiles(Level level) {
        for (int z = 0; z < DungeonGrid.GRID_SIZE; z++) {
            for (int x = 0; x < DungeonGrid.GRID_SIZE; x++) {
                DungeonGrid.Tile tile = new DungeonGrid.Tile(x, z);

                if (dataByTile.containsKey(tile)) continue;

                RoomCoreScanner.Result result = coreScanner.scan(level, tile);
                if (result == null) continue;

                DungeonRoomData data = database.findByCore(result.core());
                if (data != null) {
                    dataByTile.put(tile, data);
                }
            }
        }
    }

    public record Layout(
            Map<DungeonGrid.Tile, DungeonRoom> roomByTile,
            List<DungeonRoom> rooms
    ) {
        public DungeonRoom roomAt(DungeonGrid.Tile tile) {
            return roomByTile.get(tile);
        }

        public DungeonRoom findFirst(DungeonRoomData.Type type) {
            for (DungeonRoom room : rooms) {
                if (room.type() == type) {
                    return room;
                }
            }

            return null;
        }
    }

}

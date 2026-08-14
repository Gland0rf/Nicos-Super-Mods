package com.nico.client.dungeon;

import net.minecraft.world.level.Level;

import java.util.*;

public final class DungeonLayoutScanner {

    private final DungeonRoomDatabase database = DungeonRoomDatabase.getInstance();

    private final RoomCoreScanner coreScanner = new RoomCoreScanner();

    private Level currentLevel;

    private final Map<DungeonGrid.Tile, DungeonRoomData> dataByTile = new HashMap<>();

    public synchronized Layout scan(Level level) {
        if (currentLevel != level) {
            currentLevel = level;
            dataByTile.clear();
        }

        scanLoadedTiles(level);

        Map<DungeonRoomData, Set<DungeonGrid.Tile>> grouped = new LinkedHashMap<>();
        for (Map.Entry<DungeonGrid.Tile, DungeonRoomData> entry : dataByTile.entrySet()) {
            grouped.computeIfAbsent(entry.getValue(), ignored -> new LinkedHashSet<>()).add(entry.getKey());
        }

        Map<DungeonGrid.Tile, DungeonRoom> roomByTile = new HashMap<>();

        List<DungeonRoom> rooms = grouped.entrySet()
                .stream()
                .map(entry -> new DungeonRoom(entry.getKey(), entry.getValue()))
                .toList();

        for (DungeonRoom room : rooms) {
            for (DungeonGrid.Tile tile : room.tiles()) {
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

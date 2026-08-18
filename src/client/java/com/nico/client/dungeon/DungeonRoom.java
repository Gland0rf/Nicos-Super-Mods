package com.nico.client.dungeon;

import java.util.Set;

public record DungeonRoom (DungeonRoomData data, Set<DungeonGrid.Tile> tiles) {

    public DungeonRoom {
        tiles = Set.copyOf(tiles);
    }

    public String name() {
        return data.name();
    }

    public DungeonRoomData.Type type() {
        return data.type();
    }

    public DungeonRoomData.Shape shape() {
        return data.shape();
    }

    public boolean contains(DungeonGrid.Tile tile) {
        return tiles.contains(tile);
    }

}
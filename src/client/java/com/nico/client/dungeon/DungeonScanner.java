package com.nico.client.dungeon;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public final class DungeonScanner {

    private static final DungeonLayoutScanner LAYOUT = new DungeonLayoutScanner();
    private static final DungeonDoorScanner DOORS = new DungeonDoorScanner();
    private static final DungeonRoomGeometry GEOMETRY = new DungeonRoomGeometry();

    private DungeonScanner() { }

    public static DungeonLayoutScanner.Layout scan(Level level) {
        return LAYOUT.scan(level);
    }

    public static DungeonRoom getRoomForPlayer(Player player) {
        DungeonGrid.Tile tile = DungeonGrid.fromPlayer(player);
        if (tile == null) return null;

        return LAYOUT.scan(player.level()).roomAt(tile);
    }

    public static String getRoomNameForPlayer(Player player) {
        DungeonRoom room = getRoomForPlayer(player);
        if (room == null) return "Unknown";

        return room.name();
    }

    public static boolean isInDungeon(Player player) {
        return getRoomForPlayer(player) != null;
    }

    public static DungeonRoomGeometry.Orientation getRoomOrientation(Level level, DungeonRoom room) {
        return GEOMETRY.getOrientation(level, room);
    }

    public static boolean hasLockedWitherDoorForPlayer(Player player) {
        Level level = player.level();

        DungeonGrid.Tile tile = DungeonGrid.fromPlayer(player);
        if (tile == null) return false;

        DungeonLayoutScanner.Layout layout = LAYOUT.scan(level);

        DungeonRoom currentRoom = layout.roomAt(tile);
        if (currentRoom == null) return false;

        if (DOORS.hasClosedWitherDoor(level, currentRoom)) return true;

        // Edge case: Fairy room. It's an open door, and the wither door is inside fairy room.
        DungeonRoom fairyRoom = layout.findFirst(DungeonRoomData.Type.FAIRY);
        if (fairyRoom == null || fairyRoom == currentRoom) return false;

        if (!DOORS.roomsConnected(level, currentRoom, fairyRoom)) return false;

        return DOORS.hasClosedWitherDoor(level, fairyRoom);
    }

}

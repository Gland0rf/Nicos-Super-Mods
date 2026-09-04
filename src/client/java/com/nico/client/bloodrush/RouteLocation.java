package com.nico.client.bloodrush;

public record RouteLocation (
        RoomInfo room,
        DoorId entrance,
        DoorId exit
) {
    public boolean sameTraversal(RouteLocation other) {
        return other != null
                && room.id().equals(other.room.id())
                && entrance.matchesWithTolerance(other.entrance)
                && exit.matchesWithTolerance(other.exit);
    }

    public String connectionName() {
        return entrance.fileName() + "_to_" + exit.fileName();
    }
}

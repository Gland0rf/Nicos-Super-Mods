package com.nico.client.bloodrush;

public record RouteLocation (
        RoomInfo room,
        DoorId entrance,
        DoorId exit
) {
    public boolean sameTraversal(RouteLocation other) {
        return other != null
                && room.id().equals(other.room.id())
                && entrance.equals(other.entrance)
                && exit.equals(other.exit);
    }

    public String connectionName() {
        return entrance.fileName() + "_to_" + exit.fileName();
    }
}

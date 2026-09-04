package com.nico.client.bloodrush;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public record DoorId (int x, int y, int z) {
    public static final int TOLERANCE = 1;

    public static DoorId fromWorld(BlockPos doorAnchor, RoomInfo room) {
        BlockPos local = RouteTransforms.worldBlockToLocal(doorAnchor, room);

        return new DoorId(
                local.getX(),
                local.getY(),
                local.getZ()
        );
    }

    public boolean matchesWithTolerance(DoorId other) {
        return other != null
                && y == other.y
                && Math.abs(x - other.x) <= TOLERANCE
                && Math.abs(z - other.z) <= TOLERANCE;
    }

   public int xzDistanceTo(DoorId other) {
        if (other == null || y != other.y) {
             return Integer.MAX_VALUE;
        }

        return Math.abs(x - other.x) + Math.abs(z - other.z);
   }

    public String fileName() {
        return x + "_" + y + "_" + z;
    }
}

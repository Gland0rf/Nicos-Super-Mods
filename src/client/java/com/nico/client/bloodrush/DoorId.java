package com.nico.client.bloodrush;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public record DoorId (int x, int y, int z) {
    public static DoorId fromWorld(BlockPos doorAnchor, RoomInfo room) {
        Vec3 worldCenter = new Vec3(
                doorAnchor.getX() + 0.5,
                doorAnchor.getY() + 0.5,
                doorAnchor.getZ() + 0.5
        );

        Vec3 local = RouteTransforms.worldToLocal(worldCenter, room);

        return new DoorId(
                (int) Math.round(local.x),
                (int) Math.round(local.y),
                (int) Math.round(local.z)
        );
    }

    public String fileName() {
        return x + "_" + y + "_" + z;
    }
}

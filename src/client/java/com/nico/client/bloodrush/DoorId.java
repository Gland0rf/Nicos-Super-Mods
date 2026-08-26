package com.nico.client.bloodrush;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public record DoorId (int x, int y, int z) {
    public static DoorId fromWorld(BlockPos doorAnchor, RoomInfo room) {
        BlockPos local = RouteTransforms.worldBlockToLocal(doorAnchor, room);

        return new DoorId(
                local.getX(),
                local.getY(),
                local.getZ()
        );
    }

    public String fileName() {
        return x + "_" + y + "_" + z;
    }
}

package com.nico.client.bloodrush;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class RouteTransforms {
    private RouteTransforms() { }

    public static Vec3 worldToLocal(Vec3 world, RoomInfo room) {
        Vec3 pivot = room.rotationPivot();

        double dx = world.x - pivot.x;
        double dy = world.y - pivot.y;
        double dz = world.z - pivot.z;

        return switch (Math.floorMod(room.rotationQuarterTurns(), 4)) {
            case 0 -> new Vec3(dx, dy, dz);
            case 1 -> new Vec3(dz, dy, -dx);
            case 2 -> new Vec3(-dx, dy, -dz);
            case 3 -> new Vec3(-dz, dy, dx);
            default -> throw new IllegalStateException();
        };
    }

    public static Vec3 localToWorld(Vec3 local, RoomInfo room) {
        double x;
        double z;

        switch (Math.floorMod(room.rotationQuarterTurns(), 4)) {
            case 0 -> { x = local.x; z = local.z; }
            case 1 -> { x = -local.z; z = local.x; }
            case 2 -> { x = -local.x; z = -local.z; }
            case 3 -> { x = local.z; z = -local.x; }
            default -> throw new IllegalStateException();
        }

        Vec3 pivot = room.rotationPivot();

        return new Vec3(pivot.x + x, pivot.y + local.y, pivot.z + z);
    }

    public static BlockPos worldBlockToLocal(BlockPos world, RoomInfo room) {
        Vec3 center = new Vec3(world.getX() + 0.5, world.getY() + 0.5, world.getZ() + 0.5);

        Vec3 local = worldToLocal(center, room);

        return new BlockPos(
                (int) Math.floor(local.x),
                (int) Math.floor(local.y),
                (int) Math.floor(local.z)
        );
    }

    public static BlockPos localBlockToWorld(BlockPos local, RoomInfo room) {
        Vec3 center = new Vec3(local.getX() + 0.5, local.getY() + 0.5, local.getZ() + 0.5);

        Vec3 world = localToWorld(center, room);

        return new BlockPos(
                (int) Math.floor(world.x),
                (int) Math.floor(world.y),
                (int) Math.floor(world.z)
        );
    }
}

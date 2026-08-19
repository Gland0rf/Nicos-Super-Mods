package com.nico.client.bloodrush;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public record RouteNode (Vec3 position, BlockPos etherwarpTarget) {
    public static RouteNode normal(Vec3 position) {
        return new RouteNode(position, null);
    }

    public static RouteNode etherwarp(Vec3 position, BlockPos etherwarpTarget) {
        return new RouteNode(position, etherwarpTarget);
    }

    public boolean isEtherwarp() {
        return etherwarpTarget != null;
    }
}
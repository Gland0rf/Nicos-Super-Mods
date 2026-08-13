package com.nico.client.bloodrush;

import net.minecraft.world.phys.Vec3;

public record RoomInfo (
        String id,
        Vec3 rotationPivot,
        int rotationQuarterTurns
) {

}
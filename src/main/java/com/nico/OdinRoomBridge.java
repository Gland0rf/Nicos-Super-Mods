package com.nico;

import com.odtheking.odin.features.impl.dungeon.map.Door;
import com.odtheking.odin.features.impl.dungeon.map.MapRoom;
import com.odtheking.odin.features.impl.dungeon.map.MapScanner;
import com.odtheking.odin.utils.skyblock.dungeon.ScanUtils;
import com.odtheking.odin.utils.skyblock.dungeon.tiles.Room;
import com.odtheking.odin.utils.Vec2;
import com.odtheking.odin.utils.skyblock.dungeon.tiles.RoomData;
import com.odtheking.odin.utils.skyblock.dungeon.tiles.RoomType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

public class OdinRoomBridge {

    public static Room getRoomForPlayer(Player player) {
        Vec2 roomCenter = ScanUtils.INSTANCE.getRoomCenter(
                (int) player.getX(),
                (int) player.getZ()
        );

        for (Room room : ScanUtils.INSTANCE.getPassedRooms()) {
            boolean inside = room.getRoomComponents().stream()
                    .anyMatch(component -> component.getVec2().equals(roomCenter));

            if (inside)
                return room;
        }

        return ScanUtils.INSTANCE.scanRoom(roomCenter);
    }

    public static String getRoomNameForPlayer(Player player) {
        Room room = getRoomForPlayer(player);

        if (room == null || room.getData() == null) {
            return "Unknown";
        }

        return room == null ? null : room.getData().getName();
    }

    public static boolean hasLockedWitherDoorForPlayer(Player player) {
        Room scanRoom = getRoomForPlayer(player);

        if (scanRoom == null || scanRoom.getData() == null) {
            return false;
        }

        try {
            if (player.level() != null) {
                MapScanner.INSTANCE.scan(player.level());
            }

            if (currentRoomConnectsToFairy(scanRoom) && fairyRoomHasClosedWitherDoor(player)) {
                return true;
            }

            for (Door door : MapScanner.INSTANCE.getDoors()) {
                if (door.getType() != Door.Type.WITHER) continue;
                if (!getDoorLocked(door, player)) continue;

                if (doorTouchesScanRoom(door, scanRoom)) {
                    return true;
                }
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

        return false;
    }

    private static boolean doorTouchesScanRoom(Door door, Room room) {
        int doorX = door.getPos().getX();
        int doorZ = door.getPos().getZ();

        return room.getRoomComponents().stream().anyMatch(component -> {
            int roomX = component.getX();
            int roomZ = component.getZ();

            int dx = Math.abs(doorX - roomX);
            int dz = Math.abs(doorZ - roomZ);

            boolean northSouthDoor = dx == 0 && dz == 16;
            boolean eastWestDoor = dz == 0 && dx == 16;

            return northSouthDoor || eastWestDoor;
        });
    }


    private static boolean getDoorLocked(Door door, Player player) {
        try {
            Object posObject = door.getClass()
                    .getMethod("getPos")
                    .invoke(door);

            int x = (Integer) posObject.getClass()
                    .getMethod("getX")
                    .invoke(posObject);

            int z = (Integer) posObject.getClass()
                    .getMethod("getZ")
                    .invoke(posObject);


            return player.level()
                    .getBlockState(new BlockPos(x, 69, z))
                    .is(Blocks.COAL_BLOCK);

        } catch (Throwable throwable) {
            throwable.printStackTrace();
            return false;
        }
    }

    private static boolean fairyRoomHasClosedWitherDoor(Player player) {
        try {
            for (Door door : MapScanner.INSTANCE.getDoors()) {
                if (door.getType() != Door.Type.WITHER) continue;
                if (!doorTouchesFairyRoom(door)) continue;
                if (!getDoorLocked(door, player)) continue;

                return true;
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

        return false;
    }

    private static boolean currentRoomConnectsToFairy(Room scanRoom) {
        try {
            for (Door door : MapScanner.INSTANCE.getDoors()) {
                if (!doorTouchesScanRoom(door, scanRoom)) continue;

                if (doorTouchesFairyRoom(door)) {
                    return true;
                }
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

        return false;
    }

    private static boolean doorTouchesFairyRoom(Door door) {
        try {

            for (var tileObject : door.getRooms()) {
                MapRoom owner = tileObject.getOwner();

                if (owner == null) continue;

                RoomData data = owner.getData();

                if (data == null) continue;

                RoomType type = data.getType();

                if (String.valueOf(type).equals("FAIRY")) {
                    return true;
                }
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }

        return false;
    }

}

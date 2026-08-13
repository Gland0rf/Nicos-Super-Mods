package com.nico.client.bloodrush;

import com.nico.OdinRoomBridge;
import com.odtheking.odin.features.impl.dungeon.map.Door;
import com.odtheking.odin.features.impl.dungeon.map.MapRoom;
import com.odtheking.odin.features.impl.dungeon.map.MapScanner;
import com.odtheking.odin.features.impl.dungeon.map.Vec2i;
import com.odtheking.odin.utils.Vec2;
import com.odtheking.odin.utils.skyblock.dungeon.DungeonUtils;
import com.odtheking.odin.utils.skyblock.dungeon.ScanUtils;
import com.odtheking.odin.utils.skyblock.dungeon.tiles.Room;
import com.odtheking.odin.utils.skyblock.dungeon.tiles.RoomType;
import com.odtheking.odin.utils.skyblock.dungeon.tiles.Rotations;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Supplies the route system with dungeon information using Odin.
 *
 * The entrance is tracked when the player changes rooms. The exit is the next
 * locked wither door, the blood door, or the normal connector into fairy room
 * when fairy still contains a locked wither door.
 */
public final class RouteContext implements RouteContextProvider {

    private Room previousRoom;
    private Door entranceDoor;

    private static final Logger LOGGER = LoggerFactory.getLogger("NSM-BloodRush");

    @Override
    public Optional<RouteLocation> currentRouteLocation() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;

        if (player == null) {
            return Optional.empty();
        }

        if (!isInDungeon()) {
            reset();
            return Optional.empty();
        }

        Room room = OdinRoomBridge.getRoomForPlayer(player);

        if (room == null) {
            return Optional.empty();
        }

        if (room.getData() == null) {
            return Optional.empty();
        }

        if (room.getRotation() == Rotations.NONE) {
            return Optional.empty();
        }

        try {
            if (player.level() != null) {
                MapScanner.INSTANCE.scan(player.level());
            }

            logDoorsForRoom(room, player);

            updateEntrance(room, player);

            if (entranceDoor == null) {
                entranceDoor = findClosestDoor(
                        room,
                        player,
                        null
                );
            }

            if (entranceDoor == null) {
                return Optional.empty();
            }

            Door exitDoor = findRushExitDoor(
                    room,
                    player,
                    entranceDoor
            );

            if (exitDoor == null) {
                return Optional.empty();
            }

            if (exitDoor == entranceDoor) {
                return Optional.empty();
            }

            RoomInfo roomInfo = createRoomInfo(room);

            DoorId entrance = DoorId.fromWorld(
                    getDoorBlockPos(entranceDoor),
                    roomInfo
            );

            DoorId exit = DoorId.fromWorld(
                    getDoorBlockPos(exitDoor),
                    roomInfo
            );

            return Optional.of(
                    new RouteLocation(
                            roomInfo,
                            entrance,
                            exit
                    )
            );

        } catch (Throwable throwable) {
            return Optional.empty();
        }
    }

    @Override
    public DungeonRole currentRole() {
        if (!isInDungeon()) return null;

        try {
            var dungeonPlayer = DungeonUtils.INSTANCE.getCurrentDungeonPlayer();
            if (dungeonPlayer == null || dungeonPlayer.getClazz() == null) return null;

            return switch (String.valueOf(dungeonPlayer.getClazz()).toLowerCase()) {
                case "mage" -> DungeonRole.MAGE;
                case "archer" -> DungeonRole.ARCHER;
                case "healer" -> DungeonRole.HEALER;
                case "tank" -> DungeonRole.TANK;
                case "berserk" -> DungeonRole.BERSERK;
                default -> null;
            };
        } catch (Throwable throwable) {
            return null;
        }
    }

    @Override
    public Optional<RouteSetupPosition> currentSetupPosition() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;

        if (player == null || !isInDungeon()) {
            return Optional.empty();
        }

        Room room = OdinRoomBridge.getRoomForPlayer(player);

        if (room == null
                || room.getData() == null
                || room.getRotation() == Rotations.NONE) {
            return Optional.empty();
        }

        try {
            if (player.level() != null) {
                MapScanner.INSTANCE.scan(player.level());
            }

            Door closestDoor = findClosestDoor(
                    room,
                    player,
                    null
            );

            if (closestDoor == null) {
                return Optional.empty();
            }

            RoomInfo roomInfo = createRoomInfo(room);

            DoorId doorId = DoorId.fromWorld(
                    getDoorBlockPos(closestDoor),
                    roomInfo
            );

            return Optional.of(
                    new RouteSetupPosition(
                            roomInfo,
                            doorId
                    )
            );

        } catch (Throwable throwable) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isInDungeon() {
        return DungeonUtils.INSTANCE.getInDungeons();
    }

    /**
     * Finds the exit used for blood rush.
     *
     * Priority:
     * 1. Normal connector into Fairy if Fairy's Wither door is still closed
     * 2. Locked Wither door in the current room
     * 3. Blood door
     */
    private static Door findRushExitDoor(
            Room room,
            Player player,
            Door entrance
    ) {
        Door fairy = findFairyConnector(room, player, entrance);

        if (fairy != null) {
            return fairy;
        }

        Door blood = findBloodConnector(room, entrance);

        if (blood != null) {
            return blood;
        }

        for (Door door : MapScanner.INSTANCE.getDoors()) {
            if (sameDoor(door, entrance)) continue;
            if (!OdinRoomBridge.doorTouchesScanRoom(door, room)) continue;

            if (OdinRoomBridge.getDoorLocked(door, player)) {
                return door;
            }
        }

        return null;
    }

    /**
     * The door before Fairy is normal. If Fairy still has its locked Wither
     * door, that normal connector is the correct exit from the current room.
     */
    private static Door findFairyConnector(
            Room room,
            Player player,
            Door entrance
    ) {
        if (room.getData() != null
                && room.getData().getType() == RoomType.FAIRY) {
            return null;
        }

        for (Door door : MapScanner.INSTANCE.getDoors()) {
            if (sameDoor(door, entrance)) continue;
            if (!OdinRoomBridge.doorTouchesScanRoom(door, room)) continue;

            if (OdinRoomBridge.doorLeadsToRoomType(door, room, RoomType.FAIRY)) {
                return door;
            }
        }

        return null;
    }

    private static Door findBloodConnector(
            Room room,
            Door entrance
    ) {
        for (Door door : MapScanner.INSTANCE.getDoors()) {
            if (sameDoor(door, entrance)) continue;
            if (!OdinRoomBridge.doorTouchesScanRoom(door, room)) continue;

            if (OdinRoomBridge.doorLeadsToRoomType(door, room, RoomType.BLOOD)) {
                return door;
            }
        }

        return null;
    }

    /**
     * Records the door crossed when the player's current room changes.
     */
    private void updateEntrance(Room currentRoom, Player player) {
        if (previousRoom == null) {
            previousRoom = currentRoom;
            return;
        }

        if (sameRoom(previousRoom, currentRoom)) {
            return;
        }

        Door connecting = findConnectingDoor(previousRoom, currentRoom);
        entranceDoor = connecting != null ? connecting : findClosestDoor(currentRoom, player, null);
        previousRoom = currentRoom;
    }

    private static Door findConnectingDoor(Room first, Room second) {
        for (Door door : MapScanner.INSTANCE.getDoors()) {
            if (OdinRoomBridge.doorTouchesScanRoom(door, first) && OdinRoomBridge.doorTouchesScanRoom(door, second)) {
                return door;
            }
        }

        return null;
    }

    private static Door findClosestDoor(Room room, Player player, Door excluded) {
        Door closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (Door door : MapScanner.INSTANCE.getDoors()) {
            if (door == excluded || !OdinRoomBridge.doorTouchesScanRoom(door, room)) continue;

            Vec2i pos = door.getPos();
            double dx = pos.getX() + 0.5 - player.getX();
            double dz = pos.getZ() + 0.5 - player.getZ();
            double distance = dx * dx + dz * dz;

            if (distance < closestDistance) {
                closestDistance = distance;
                closest = door;
            }
        }

        return closest;
    }

    private static boolean sameRoom(Room first, Room second) {
        if (first == second) {
            return true;
        }

        if (first == null || second == null) {
            return false;
        }

        if (first.getData() == null || second.getData() == null) {
            return false;
        }

        if (!first.getData().getName().equals(second.getData().getName())) {
            return false;
        }

        return first.getRoomComponents().equals(second.getRoomComponents());
    }

    private static boolean sameDoor(Door first, Door second) {
        if (first == second) {
            return true;
        }

        if (first == null || second == null) {
            return false;
        }

        return first.getPos().getX() == second.getPos().getX()
                && first.getPos().getZ() == second.getPos().getZ();
    }

    private static RoomInfo createRoomInfo(Room room) {
        BlockPos clayPos = room.getClayPos();
        Vec3 pivot = new Vec3(clayPos.getX(), 0, clayPos.getZ());

        return new RoomInfo(room.getData().getName(), pivot, quarterTurns(room.getRotation()));
    }

    private static int quarterTurns(Rotations rotation) {
        return switch (rotation) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            case NONE -> throw new IllegalStateException("Room rotation is unknown.");
        };
    }

    private static BlockPos getDoorBlockPos(Door door) {
        Vec2i pos = door.getPos();
        return new BlockPos(pos.getX(), 69, pos.getZ());
    }

    private void reset() {
        previousRoom = null;
        entranceDoor = null;
    }

    private static void logDoorsForRoom(Room room, Player player) {
        LOGGER.info("[Route] --- Doors touching {} ---", room.getData().getName());

        for (Door door : MapScanner.INSTANCE.getDoors()) {
            boolean touches = OdinRoomBridge.doorTouchesScanRoom(
                    door,
                    room
            );

            if (!touches) {
                continue;
            }

            boolean locked = false;

            if (door.getType() == Door.Type.WITHER) {
                locked = OdinRoomBridge.getDoorLocked(
                        door,
                        player
                );
            }

            boolean fairy = OdinRoomBridge.doorTouchesFairyRoom(door);

            BlockPos pos = new BlockPos(
                    door.getPos().getX(),
                    69,
                    door.getPos().getZ()
            );

            LOGGER.info(
                    "[Route] Door type={} pos=({}, {}) locked={} fairy={} block={}",
                    door.getType(),
                    door.getPos().getX(),
                    door.getPos().getZ(),
                    locked,
                    fairy,
                    player.level().getBlockState(pos)
            );
        }

        LOGGER.info("[Route] --- End doors ---");
    }
}

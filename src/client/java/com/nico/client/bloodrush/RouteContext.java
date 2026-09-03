package com.nico.client.bloodrush;

import com.nico.client.dungeon.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Supplies the route system with dungeon information using Odin.
 *
 * The entrance is tracked when the player changes rooms. The exit is the next
 * locked wither door, the blood door, or the normal connector into fairy room
 * when fairy still contains a locked wither door.
 */
public final class RouteContext implements RouteContextProvider {

    private static final DungeonDoorScanner DOORS = new DungeonDoorScanner();
    private static final Logger LOGGER = LoggerFactory.getLogger("NSM-BloodRush");

    private DungeonRoom previousRoom;
    private DungeonDoorScanner.Door entranceDoor;

    @Override
    public Optional<RouteLocation> currentRouteLocation() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;

        if (player == null || player.level() == null) {
            reset();
            return Optional.empty();
        }

        if (!isInDungeon()) {
            reset();
            return Optional.empty();
        }

        Level level = player.level();
        DungeonLayoutScanner.Layout layout = DungeonScanner.scan(level);
        DungeonRoom room = roomForPlayer(layout, player);

        if (room == null) {
            return Optional.empty();
        }

        RoomInfo roomInfo = createRoomInfo(level, room);
        if (roomInfo == null) {
            return Optional.empty();
        }

        try {
            logDoorsForRoom(level, layout, room);

            updateEntrance(level, layout, room, player);

            if (entranceDoor == null) {
                entranceDoor = findClosestDoor(level, layout, room, player, null);
            }

            if (entranceDoor == null) {
                return Optional.empty();
            }

            DungeonDoorScanner.Door exitDoor = findRushExitDoor(level, layout, room, entranceDoor);
            if (exitDoor == null || sameDoor(exitDoor, entranceDoor)) {
                return Optional.empty();
            }

            DoorId entrance = DoorId.fromWorld(entranceDoor.pos(), roomInfo);
            DoorId exit = DoorId.fromWorld(exitDoor.pos(), roomInfo);

            return Optional.of(new RouteLocation(roomInfo, entrance, exit));
        } catch (Throwable throwable) {
            return Optional.empty();
        }
    }

    @Override
    public DungeonRole currentRole() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;

        if (player == null || !isInDungeon()) return null;

        try {
            String dungeonClass = DungeonTeammateScanner.getDungeonClassForPlayer(player);
            if (dungeonClass == null) return null;

            return switch (dungeonClass.toLowerCase(Locale.ROOT)) {
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

        if (player == null || player.level() == null || !isInDungeon()) {
            return Optional.empty();
        }

        try {
            Level level = player.level();
            DungeonLayoutScanner.Layout layout = DungeonScanner.scan(level);
            DungeonRoom room = roomForPlayer(layout, player);
            if (room == null) {
                return Optional.empty();
            }

            RoomInfo roomInfo = createRoomInfo(level, room);
            if (roomInfo == null) {
                return Optional.empty();
            }

            DungeonDoorScanner.Door closestDoor = findClosestDoor(level, layout, room, player, null);
            if (closestDoor == null) {
                return Optional.empty();
            }

            DoorId doorId = DoorId.fromWorld(closestDoor.pos(), roomInfo);

            return Optional.of(new RouteSetupPosition(roomInfo, doorId));
        } catch (Throwable throwable) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isInDungeon() {
        Player player = Minecraft.getInstance().player;
        return player != null && DungeonScanner.isInDungeon(player);
    }

    /**
     * Finds the exit used for blood rush.
     *
     * Priority:
     * 1. Normal connector into Fairy if Fairy's Wither door is still closed
     * 2. Blood door
     * 3. Locked Wither door in the current room
     */
    private static DungeonDoorScanner.Door findRushExitDoor(
            Level level,
            DungeonLayoutScanner.Layout layout,
            DungeonRoom room,
            DungeonDoorScanner.Door entrance
    ) {
        DungeonDoorScanner.Door fairy = findFairyConnector(level, layout, room, entrance);

        if (fairy != null) {
            return fairy;
        }

        DungeonDoorScanner.Door blood = findBloodConnector(level, layout, room, entrance);

        if (blood != null) {
            return blood;
        }

        for (DungeonDoorScanner.Door door : DOORS.getDoorsForRoom(level, layout, room)) {
            if (sameDoor(door, entrance)) continue;

            if (DOORS.isClosedWitherDoor(level, door)) {
                return door;
            }
        }

        return null;
    }

    /**
     * The door before Fairy is normal. If Fairy still has its locked Wither
     * door, that normal connector is the correct exit from the current room.
     */
    private static DungeonDoorScanner.Door findFairyConnector(
            Level level,
            DungeonLayoutScanner.Layout layout,
            DungeonRoom room,
            DungeonDoorScanner.Door entrance
    ) {
        if (room.type() == DungeonRoomData.Type.FAIRY) return null;

        for (DungeonDoorScanner.Door door : DOORS.getDoorsForRoom(level, layout, room)) {
            if (sameDoor(door, entrance)) continue;

            DungeonRoom otherRoom = door.otherRoom();
            if (otherRoom != null && otherRoom.type() == DungeonRoomData.Type.FAIRY) {
                return door;
            }
        }

        return null;
    }


    private static DungeonDoorScanner.Door findBloodConnector(
            Level level,
            DungeonLayoutScanner.Layout layout,
            DungeonRoom room,
            DungeonDoorScanner.Door entrance
    ) {
        for (DungeonDoorScanner.Door door : DOORS.getDoorsForRoom(level, layout, room)) {
            if (sameDoor(door, entrance)) continue;

            DungeonRoom otherRoom = door.otherRoom();
            if ((otherRoom != null && otherRoom.type() == DungeonRoomData.Type.BLOOD)
                    || DOORS.getType(level, door) == DungeonDoorScanner.Type.BLOOD) {
                return door;
            }
        }

        return null;
    }

    /**
     * Records the door crossed when the player's current room changes.
     */
    private void updateEntrance(
            Level level,
            DungeonLayoutScanner.Layout layout,
            DungeonRoom currentRoom,
            Player player
    ) {
        if (previousRoom == null) {
            previousRoom = currentRoom;
            return;
        }

        if (sameRoom(previousRoom, currentRoom)) {
            return;
        }

        DungeonDoorScanner.Door connecting = DOORS.findConnectingDoor(level, layout, previousRoom, currentRoom);
        entranceDoor = connecting != null ? connecting : findClosestDoor(level, layout, currentRoom, player, null);
        previousRoom = currentRoom;
    }

    private static DungeonDoorScanner.Door findClosestDoor(
            Level level,
            DungeonLayoutScanner.Layout layout,
            DungeonRoom room,
            Player player,
            DungeonDoorScanner.Door excluded
    ) {
        DungeonDoorScanner.Door closest = null;
        double closestDistance = Double.MAX_VALUE;

        for (DungeonDoorScanner.Door door : DOORS.getDoorsForRoom(level, layout, room)) {
            if (sameDoor(door, excluded)) continue;

            BlockPos pos = door.pos();
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

    private static DungeonRoom roomForPlayer(DungeonLayoutScanner.Layout layout, Player player) {
        DungeonGrid.Tile tile = DungeonGrid.fromPlayer(player);
        return tile == null ? null : layout.roomAt(tile);
    }

    private static boolean sameRoom(DungeonRoom first, DungeonRoom second) {
        return Objects.equals(first, second);
    }

    private static boolean sameDoor(DungeonDoorScanner.Door first, DungeonDoorScanner.Door second) {
        return first == second || (first != null && second != null && first.pos().equals(second.pos()));
    }

    private static RoomInfo createRoomInfo(Level level, DungeonRoom room) {
        DungeonRoomGeometry.Orientation orientation = DungeonScanner.getRoomOrientation(level, room);
        if (orientation == null) return null;

        BlockPos pivotPos = orientation.pivot();
        Vec3 pivot = new Vec3(pivotPos.getX(), 0, pivotPos.getZ());

        return new RoomInfo(room.name(), pivot, orientation.quarterTurns());
    }

    private void reset() {
        previousRoom = null;
        entranceDoor = null;
    }

    private static void logDoorsForRoom(Level level, DungeonLayoutScanner.Layout layout, DungeonRoom room) {
        if (!LOGGER.isDebugEnabled()) return;

        LOGGER.info("[Route] --- Doors touching {} ---", room.name());

        for (DungeonDoorScanner.Door door : DOORS.getDoorsForRoom(level, layout, room)) {
            DungeonDoorScanner.Type type = DOORS.getType(level, door);
            boolean locked = type == DungeonDoorScanner.Type.WITHER
                    && DOORS.isClosedWitherDoor(level, door);
            boolean fairy = door.otherRoom() != null
                    && door.otherRoom().type() == DungeonRoomData.Type.FAIRY;

            BlockPos pos = door.pos();

            LOGGER.debug(
                    "[Route] Door type={} pos=({}, {}) locked={} fairy={} block={}",
                    type,
                    pos.getX(),
                    pos.getZ(),
                    locked,
                    fairy,
                    level.getBlockState(pos)
            );
        }

        LOGGER.info("[Route] --- End doors ---");
    }
}

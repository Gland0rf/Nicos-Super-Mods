package com.nico.client.bloodrush;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.*;

public final class RouteEditor {

    private final Minecraft minecraft = Minecraft.getInstance();
    private final RouteContextProvider context;
    private final RouteRepository repository;
    private final BloodRushState bloodRushState;

    private RecordingSession recording;
    private RouteKey forcedPreview;
    private boolean hidden;

    private List<DisplayRoute> allRoomPreview = List.of();
    private List<DisplayRoute> manualPreview = List.of();
    private RouteSetupPosition previewStart;
    private CopiedRoute copiedRoute;

    public RouteEditor(
            RouteContextProvider context,
            RouteRepository repository,
            BloodRushState bloodRushState
    ) {
        this.context = context;
        this.repository = repository;
        this.bloodRushState = bloodRushState;
    }

    public Result start(Collection<RouteKey> keys) {
        manualPreview = List.of();
        allRoomPreview = List.of();

        if (minecraft.player == null) {
            return Result.fail("Player is not available.");
        }

        LinkedHashSet<RouteKey> uniqueKeys = new LinkedHashSet<>(keys);
        if (uniqueKeys.isEmpty()) {
            return Result.fail("No route classes were selected.");
        }

        Optional<RouteSetupPosition> setupOptional = context.currentSetupPosition();

        if (setupOptional.isEmpty()) {
            return Result.fail(
                    "Could not determine the nearest door. Stand at the route's starting door."
            );
        }

        RouteSetupPosition start = setupOptional.get();

        Vec3 firstPoint = RouteTransforms.worldToLocal(
                minecraft.player.position(),
                start.room()
        );

        recording = new RecordingSession(
                start.room(),
                start.door(),
                uniqueKeys
        );

        recording.nodes.add(RouteNode.normal(firstPoint));

        forcedPreview = recording.primaryKey();
        hidden = false;

        return Result.ok(
                "Recording "
                        + routeNames(recording.keys)
                        + " from door "
                        + start.door().fileName()
                        + " | Point #1 added."
        );
    }

    public Result addPoint() {
        if (recording == null) {
            return Result.fail("No route is currently being recorded.");
        }

        if (minecraft.player == null) {
            return Result.fail("Player is not available.");
        }

        Vec3 local = RouteTransforms.worldToLocal(
                minecraft.player.position(),
                recording.room
        );

        recording.nodes.add(
                RouteNode.normal(local)
        );

        return Result.ok(
                "Added point #" + recording.nodes.size() + "."
        );
    }

    public Result addEtherwarp() {
        if (recording == null) {
            return Result.fail("No route is currently being recorded.");
        }
        if (minecraft.player == null) {
            return Result.fail("Player is not available.");
        }

        HitResult hit = minecraft.player.pick(60.0D, 1.0F, false);

        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return Result.fail("You are not looking at a block.");
        }

        BlockPos worldTarget = blockHit.getBlockPos();

        if (minecraft.level.getBlockState(worldTarget).isAir()) {
            return Result.fail("You are not looking at a solid block.");
        }

        RoomInfo room = recording.room;

        Vec3 currentPosition = RouteTransforms.worldToLocal(minecraft.player.position(), room);
        BlockPos localTarget = RouteTransforms.worldBlockToLocal(worldTarget, room);
        RouteNode etherwarp = RouteNode.etherwarp(currentPosition, localTarget);

        /*
         * If we already added a point right here, convert that point
         * into an Etherwarp instead of creating a duplicate.
         */
        if (!recording.nodes.isEmpty()) {
            int lastIndex = recording.nodes.size() - 1;
            RouteNode last = recording.nodes.get(lastIndex);

            if (last.position().distanceToSqr(currentPosition) < 0.04) {
                recording.nodes.set(lastIndex, etherwarp);

                return Result.ok(
                        "Set point #" + (lastIndex + 1)
                                + " as Etherwarp -> "
                                + worldTarget.getX() + ", "
                                + worldTarget.getY() + ", "
                                + worldTarget.getZ()
                );
            }
        }

        recording.nodes.add(etherwarp);

        return Result.ok(
                "Added Etherwarp point #" + recording.nodes.size()
                        + " -> "
                        + worldTarget.getX() + ", "
                        + worldTarget.getY() + ", "
                        + worldTarget.getZ()
        );
    }

    public Result addBreakerBlock() {
        if (recording == null) {
            return Result.fail("No route is currently being recorded.");
        }

        if (minecraft.player == null || minecraft.level == null) {
            return Result.fail("Player is not available.");
        }

        HitResult hit = minecraft.player.pick(60.0D, 1.0F, false);

        if (!(hit instanceof BlockHitResult blockHit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return Result.fail("You are not looking at a block.");
        }

        BlockPos worldTarget = blockHit.getBlockPos();

        if (minecraft.level.getBlockState(worldTarget).isAir()) {
            return Result.fail("You are not looking at a solid block.");
        }

        BlockPos localTarget = RouteTransforms.worldBlockToLocal(
                worldTarget,
                recording.room
        );

        if (!recording.breakerBlocks.add(localTarget)) {
            return Result.fail("That Dungeonbreaker block is already marked.");
        }

        return Result.ok(
                "Added Dungeonbreaker block at "
                        + worldTarget.getX() + ", "
                        + worldTarget.getY() + ", "
                        + worldTarget.getZ() + "."
        );
    }

    public Result undo() {
        if (recording == null) {
            return Result.fail("No route is currently being recorded.");
        }
        if (recording.nodes.size() <= 1) {
            return Result.fail("There are no additional points to undo.");
        }

        recording.nodes.remove(recording.nodes.size() - 1);
        return Result.ok("Removed the last route point.");
    }

    public Result removePoint(int oneBasedIndex) {
        if (recording == null) {
            return Result.fail("No route is currently being recorded.");
        }

        int index = oneBasedIndex - 1;
        if (index < 0 || index >= recording.nodes.size()) {
            return Result.fail("Point " + oneBasedIndex + " does not exist.");
        }

        recording.nodes.remove(index);
        return Result.ok("Removed point " + oneBasedIndex + ".");
    }

    public Result save() {
        if (recording == null) {
            return Result.fail("No route is currently being recorded.");
        }

        if (recording.nodes.size() < 2) {
            return Result.fail("A route needs at least 2 committed points.");
        }

        DoorId exitDoor;

        if (recording.exitOverride != null) {
            exitDoor = recording.exitOverride;
        } else {
            Optional<RouteSetupPosition> setupOptional =
                    context.currentSetupPosition();

            if (setupOptional.isEmpty()) {
                return Result.fail(
                        "Could not determine the exit door. Stand near it or use /nsm route exit first."
                );
            }

            RouteSetupPosition end = setupOptional.get();

            if (!recording.room.id().equals(end.room().id())) {
                return Result.fail(
                        "The exit must be in the same room where recording started."
                );
            }

            exitDoor = end.door();
        }

        if (recording.entrance.matchesWithTolerance(exitDoor)) {
            return Result.fail(
                    "The exit door cannot be the same as the entrance door."
            );
        }

        RouteLocation location = new RouteLocation(
                recording.room,
                recording.entrance,
                exitDoor
        );

        List<RouteKey> existing = recording.keys.stream()
                .filter(key -> repository.get(location, key).nodes().size() >= 2)
                .toList();

        if (!existing.isEmpty()) {
            return Result.fail(
                    recording.primaryKey().displayName()
                            + " already has a route for "
                            + location.entrance().fileName()
                            + " -> "
                            + location.exit().fileName()
                            + ". Delete it first if you want to replace it."
            );
        }

        try {
            for (RouteKey key : recording.keys) {
                repository.save(
                        location,
                        key,
                        recording.nodes,
                        recording.breakerBlocks
                );
            }

            String message =
                    "Saved "
                            + routeNames(recording.keys)
                            + " | "
                            + location.entrance().fileName()
                            + " -> "
                            + location.exit().fileName()
                            + " | "
                            + recording.nodes.size()
                            + " points.";

            recording = null;
            forcedPreview = null;
            hidden = false;

            return Result.ok(message);

        } catch (IOException exception) {
            exception.printStackTrace();
            return Result.fail("Could not save the route.");
        }
    }

    public Result cancel() {
        if (recording == null) {
            return Result.fail("Nothing is being recorded.");
        }

        recording = null;
        return Result.ok("Route recording cancelled.");
    }

    public Result deleteRoute(RouteKey key) {
        Optional<RouteLocation> locationOptional = context.currentRouteLocation();
        if (locationOptional.isEmpty()) {
            return Result.fail("Could not determine the current room traversal.");
        }

        RouteLocation location = locationOptional.get();
        try {
            repository.delete(location, key);

            if (recording != null
                    && recording.primaryKey() == key
                    && recording.room.id().equals(location.room().id())
                    && recording.entrance.matchesWithTolerance(location.entrance())) {
                recording = null;
            }
            if (forcedPreview == key) {
                forcedPreview = null;
            }

            return Result.ok("Deleted " + key.displayName() + " for this entrance -> exit.");
        } catch (IOException exception) {
            exception.printStackTrace();
            return Result.fail("Could not delete the route.");
        }
    }

    public Result clearConnection() {
        Optional<RouteLocation> locationOptional = context.currentRouteLocation();
        if (locationOptional.isEmpty()) {
            return Result.fail("Could not determine the current room traversal.");
        }

        RouteLocation location = locationOptional.get();
        try {
            repository.deleteConnection(location);
            if (recording != null
                    && recording.room.id().equals(location.room().id())
                    && recording.entrance.equals(location.entrance())) {
                recording = null;
            }
            forcedPreview = null;
            return Result.ok("Deleted every route for this entrance -> exit.");
        } catch (IOException exception) {
            exception.printStackTrace();
            return Result.fail("Could not clear this entrance -> exit.");
        }
    }

    public Result resetRoom() {
        Optional<RouteLocation> locationOptional = context.currentRouteLocation();
        if (locationOptional.isEmpty()) {
            return Result.fail("NSM could not detect the current room.");
        }

        RouteLocation location = locationOptional.get();
        try {
            repository.deleteRoom(location.room().id());
            if (recording != null
                    && recording.room.id().equals(location.room().id())) {
                recording = null;
            }
            forcedPreview = null;
            return Result.ok("Removed every entrance/exit route for this room.");
        } catch (IOException exception) {
            exception.printStackTrace();
            return Result.fail("Could not reset this room.");
        }
    }

    public Result preview(RouteKey key) {
        Optional<RouteSetupPosition> setupOptional = context.currentSetupPosition();
        if (setupOptional.isEmpty()) {
            return Result.fail("Could not determine the current room.");
        }

        RoomInfo room = setupOptional.get().room();

        List<RouteRepository.SavedRoute> routes = repository.getAllForRoom(room).stream()
                .filter(route -> route.key() == key)
                .toList();

        if (routes.isEmpty()) {
            return Result.fail("No " + key.displayName() + " routes are saved in this room.");
        }

        manualPreview = toDisplayRoutes(routes);
        forcedPreview = null;
        hidden = false;

        return Result.ok(
                "Showing all " + routes.size() + " "
                        + key.displayName() + " routes in this room."
        );
    }

    public Result previewFrom(RouteKey key, Integer oneBasedIndex) {
        Optional<RouteSetupPosition> setupOptional = context.currentSetupPosition();
        if (setupOptional.isEmpty()) {
            return Result.fail("Could not determine the nearest door.");
        }

        RouteSetupPosition setup = setupOptional.get();

        List<RouteRepository.SavedRoute> routes = repository.getAllForRoom(setup.room()).stream()
                .filter(route -> route.key() == key)
                .filter(route -> route.location().entrance().matchesWithTolerance(setup.door()))
                .sorted(Comparator.comparing(route -> route.location().exit().fileName()))
                .toList();

        if (routes.isEmpty()) {
            return Result.fail(
                    "No " + key.displayName()
                            + " routes start at " + setup.door().fileName() + "."
            );
        }

        forcedPreview = null;
        hidden = false;

        if (oneBasedIndex != null) {
            int index = oneBasedIndex - 1;

            if (index < 0 || index >= routes.size()) {
                return Result.fail(
                        "Route index must be between 1 and " + routes.size() + "."
                );
            }

            RouteRepository.SavedRoute selected = routes.get(index);
            manualPreview = toDisplayRoutes(List.of(selected));

            return Result.ok(
                    "Showing " + key.displayName()
                            + " route #" + oneBasedIndex
                            + " | " + setup.door().fileName()
                            + " -> " + selected.location().exit().fileName()
            );
        }

        manualPreview = toDisplayRoutes(routes);

        StringBuilder message = new StringBuilder(
                "Showing " + routes.size() + " "
                        + key.displayName() + " routes from "
                        + setup.door().fileName()
        );

        for (int i = 0; i < routes.size(); i++) {
            message.append("\n#")
                    .append(i + 1)
                    .append(" -> ")
                    .append(routes.get(i).location().exit().fileName());
        }

        return Result.ok(message.toString());
    }

    public Result setPreviewStart() {
        Optional<RouteSetupPosition> setupOptional = context.currentSetupPosition();

        if (setupOptional.isEmpty()) {
            return Result.fail("Could not determine the nearest door.");
        }

        previewStart = setupOptional.get();

        return Result.ok(
                "Preview start set to " + previewStart.door().fileName()
                        + ". Stand at the exit and use /nsm route preview to."
        );
    }

    public Result previewTo() {
        if (previewStart == null) {
            return Result.fail(
                    "Set a start first with /nsm route preview from."
            );
        }

        Optional<RouteSetupPosition> endOptional = context.currentSetupPosition();

        if (endOptional.isEmpty()) {
            return Result.fail("Could not determine the nearest exit door.");
        }

        RouteSetupPosition end = endOptional.get();

        if (!previewStart.room().id().equals(end.room().id())) {
            return Result.fail("Start and exit must be in the same room.");
        }

        List<RouteRepository.SavedRoute> routes =
                repository.getAllForRoom(previewStart.room()).stream()
                        .filter(route -> route.location().entrance().matchesWithTolerance(previewStart.door()))
                        .filter(route -> route.location().exit().matchesWithTolerance(end.door()))
                        .toList();

        if (routes.isEmpty()) {
            return Result.fail(
                    "No routes saved for "
                            + previewStart.door().fileName()
                            + " -> " + end.door().fileName() + "."
            );
        }

        manualPreview = toDisplayRoutes(routes);
        forcedPreview = null;
        hidden = false;

        return Result.ok(
                "Showing " + routes.size()
                        + " routes | "
                        + previewStart.door().fileName()
                        + " -> " + end.door().fileName()
        );
    }

    public Result auto() {
        manualPreview = List.of();
        previewStart = null;
        allRoomPreview = List.of();
        forcedPreview = null;
        hidden = false;
        return Result.ok("Automatic class route enabled.");
    }

    public Result hide() {
        hidden = true;
        return Result.ok("Routes hidden.");
    }

    public List<DisplayRoute> getDisplayRoutes(float partialTick) {
        if (hidden || minecraft.player == null) {
            return List.of();
        }

        if (recording != null) {
            List<RouteNode> nodes = new ArrayList<>(recording.nodes);

            Vec3 playerWorld = minecraft.player.getPosition(partialTick);
            Vec3 playerLocal = RouteTransforms.worldToLocal(
                    playerWorld,
                    recording.room
            );

            if (nodes.isEmpty()
                    || nodes.get(nodes.size() - 1).position().distanceToSqr(playerLocal) > 0.000001) {
                nodes.add(RouteNode.normal(playerLocal));
            }

            RouteLocation displayLocation = new RouteLocation(
                    recording.room,
                    recording.entrance,
                    recording.entrance
            );

            return List.of(new DisplayRoute(
                    recording.primaryKey(),
                    displayLocation,
                    List.copyOf(nodes),
                    List.copyOf(recording.breakerBlocks),
                    true,
                    recording.nodes.size()
            ));
        }

        if (!manualPreview.isEmpty()) {
            return manualPreview;
        }

        // Explicit /nsm route all preview.
        // Don't require a blood-rush exit to be detectable.
        if (!allRoomPreview.isEmpty()) {
            return allRoomPreview;
        }

        // Normal routes disappear once Blood is open.
        if (bloodRushState.isBloodOpen()) {
            return List.of();
        }

        Optional<RouteLocation> locationOptional =
                context.currentRouteLocation();

        if (locationOptional.isEmpty()) {
            return List.of();
        }

        RouteLocation current = locationOptional.get();

        RouteKey selected = forcedPreview != null
                ? forcedPreview
                : getAutomaticRoute();

        if (selected == null) {
            return List.of();
        }

        RouteRepository.SavedRoute saved = repository.get(current, selected);

        List<RouteNode> nodes = saved.nodes();

        if (nodes.size() < 2) {
            return List.of();
        }

        return List.of(new DisplayRoute(
                selected,
                current,
                nodes,
                saved.breakerBlocks(),
                false,
                0
        ));
    }

    public Result setExitDoor() {
        if (recording == null) {
            return Result.fail("No route is currently being recorded.");
        }

        Optional<RouteSetupPosition> setupOptional = context.currentSetupPosition();

        if (setupOptional.isEmpty()) {
            return Result.fail("Could not determine the nearest door.");
        }

        RouteSetupPosition setup = setupOptional.get();

        if (!recording.room.id().equals(setup.room().id())) {
            return Result.fail("The exit door must be in the same room.");
        }

        if (recording.entrance.matchesWithTolerance(setup.door())) {
            return Result.fail("The exit door cannot be the entrance door.");
        }

        recording.exitOverride = setup.door();

        RouteLocation location = new RouteLocation(
                recording.room,
                recording.entrance,
                recording.exitOverride
        );

        List<RouteKey> existing = recording.keys.stream()
                .filter(key -> repository.get(location, key).nodes().size() >= 2)
                .toList();

        if (!existing.isEmpty()) {
            return Result.ok(
                    "Exit set to "
                            + setup.door().fileName()
                            + ". WARNING: "
                            + routeNames(existing)
                            + " already has a route for this entrance -> exit."
            );
        }

        return Result.ok(
                "Exit door set to " + setup.door().fileName() + "."
        );
    }

    public Result showAllRoomRoutes() {
        if (recording != null) {
            return Result.fail("Finish or cancel the current recording first.");
        }

        Optional<RouteSetupPosition> setupOptional =
                context.currentSetupPosition();

        if (setupOptional.isEmpty()) {
            return Result.fail("Could not determine the current room.");
        }

        RoomInfo room = setupOptional.get().room();

        List<RouteRepository.SavedRoute> saved =
                repository.getAllForRoom(room);

        if (saved.isEmpty()) {
            return Result.fail("There are no saved routes in this room.");
        }

        allRoomPreview = saved.stream()
                .map(route -> new DisplayRoute(
                        route.key(),
                        route.location(),
                        route.nodes(),
                        route.breakerBlocks(),
                        false,
                        0
                ))
                .toList();

        forcedPreview = null;
        hidden = false;

        return Result.ok(
                "Showing all " + allRoomPreview.size()
                        + " saved routes in " + room.id() + "."
        );
    }

    public Result copyRoute(String fileName) {
        Optional<RouteSetupPosition> setupOptional = context.currentSetupPosition();

        if (setupOptional.isEmpty()) {
            return Result.fail("Could not determine the current room.");
        }

        RoomInfo room = setupOptional.get().room();

        String requested = fileName
                .replace('\\', '/')
                .strip();

        List<RouteRepository.SavedRoute> all =
                repository.getAllForRoom(room);

        List<RouteRepository.SavedRoute> exact = all.stream()
                .filter(route -> routeRelativeFileName(route)
                        .equalsIgnoreCase(requested))
                .toList();

        List<RouteRepository.SavedRoute> matches = exact;

        if (matches.isEmpty()) {
            matches = all.stream()
                    .filter(route -> (
                            route.key().fileName() + ".json"
                    ).equalsIgnoreCase(requested))
                    .toList();
        }

        if (matches.isEmpty()) {
            return Result.fail(
                    "Could not find route file \"" + requested + "\" in this room."
            );
        }

        if (matches.size() > 1) {
            String options = matches.stream()
                    .map(RouteEditor::routeRelativeFileName)
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("");

            return Result.fail(
                    "\"" + requested + "\" matches multiple routes. Use:\n" + options
            );
        }

        RouteRepository.SavedRoute source = matches.getFirst();

        copiedRoute = new CopiedRoute(
                source.location().room().id(),
                source.location().entrance(),
                List.copyOf(source.nodes()),
                Set.copyOf(source.breakerBlocks())
        );

        return Result.ok(
                "Copied "
                        + routeRelativeFileName(source)
                        + " | "
                        + source.nodes().size()
                        + " points."
        );
    }

    public Result pasteRoute() {
        if (recording == null) {
            return Result.fail(
                    "Start a recording first with /nsm route record <class>."
            );
        }

        if (copiedRoute == null) {
            return Result.fail(
                    "Nothing has been copied. Use /nsm route copy <filename> first."
            );
        }

        if (!recording.room.id().equals(copiedRoute.roomId())) {
            return Result.fail("The copied route belongs to a different room.");
        }

        if (!recording.entrance.matchesWithTolerance(copiedRoute.entrance())) {
            return Result.fail(
                    "The copied route starts at "
                            + copiedRoute.entrance().fileName()
                            + ", but this recording starts at "
                            + recording.entrance.fileName()
                            + "."
            );
        }

        recording.nodes.clear();
        recording.nodes.addAll(copiedRoute.nodes());

        recording.breakerBlocks.clear();
        recording.breakerBlocks.addAll(copiedRoute.breakerBlocks());

        recording.exitOverride = null;

        return Result.ok(
                "Pasted "
                        + recording.nodes.size()
                        + " points | "
                        + recording.breakerBlocks.size()
                        + " Dungeonbreaker blocks."
                        + "Exit is not set."
        );
    }

    private static List<DisplayRoute> toDisplayRoutes(
            List<RouteRepository.SavedRoute> routes
    ) {
        return routes.stream()
                .map(route -> new DisplayRoute(
                        route.key(),
                        route.location(),
                        route.nodes(),
                        route.breakerBlocks(),
                        false,
                        0
                ))
                .toList();
    }

    private RouteKey getAutomaticRoute() {
        DungeonRole role = context.currentRole();

        if (role == null) {
            return null;
        }

        return switch (role) {
            case HEALER -> RouteKey.HEALER;
            case MAGE -> RouteKey.MAGE;
            case ARCHER -> RouteKey.ARCHER;
            case TANK, BERSERK -> null;
        };
    }

    private static String routeNames(Collection<RouteKey> keys) {
        return keys.stream()
                .map(RouteKey::displayName)
                .reduce((a, b) -> a + " + " + b)
                .orElse("Unknown");
    }

    private static String routeRelativeFileName(
            RouteRepository.SavedRoute route
    ) {
        return route.location().connectionName()
                + "/"
                + route.key().fileName()
                + ".json";
    }

    public record DisplayRoute(
            RouteKey key,
            RouteLocation location,
            List<RouteNode> nodes,
            List<BlockPos> breakerBlocks,
            boolean editing,
            int committedPoints
    ) {
    }

    public record Result(boolean success, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    private record CopiedRoute(
            String roomId,
            DoorId entrance,
            List<RouteNode> nodes,
            Set<BlockPos> breakerBlocks
    ) {
    }

    private static final class RecordingSession {
        private final RoomInfo room;
        private final DoorId entrance;
        private final LinkedHashSet<RouteKey> keys;
        private final List<RouteNode> nodes = new ArrayList<>();
        private final Set<BlockPos> breakerBlocks = new LinkedHashSet<>();

        private DoorId exitOverride;

        private RecordingSession(
                RoomInfo room,
                DoorId entrance,
                Collection<RouteKey> keys
        ) {
            this.room = room;
            this.entrance = entrance;
            this.keys = new LinkedHashSet<>(keys);
        }

        private RouteKey primaryKey() {
            return keys.iterator().next();
        }
    }
}

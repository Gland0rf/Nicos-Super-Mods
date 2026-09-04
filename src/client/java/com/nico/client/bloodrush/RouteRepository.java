package com.nico.client.bloodrush;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

public final class RouteRepository {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path root = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("nicos_super_mods")
            .resolve("blood_routes");

    private final Map<CacheKey, SavedRoute> cache = new HashMap<>();

    public SavedRoute get(RouteLocation location, RouteKey route) {
        CacheKey key = CacheKey.of(location, route);

        SavedRoute cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        SavedRoute loaded = loadFromDisk(location, route);
        cache.put(key, loaded);

        return loaded;
    }

    public void save(RouteLocation location, RouteKey route, List<RouteNode> points, Set<BlockPos> breakerBlocks) throws IOException {
        Path file = getFile(location, route);
        Files.createDirectories(file.getParent());

        StoredRoute stored = new StoredRoute();
        stored.version = 1;
        stored.room = location.room().id();
        stored.entrance = location.entrance();
        stored.exit = location.exit();
        stored.route = route.name();

        for (RouteNode point : points) {
            StoredPoint storedPoint = new StoredPoint();

            storedPoint.x = point.position().x;
            storedPoint.y = point.position().y;
            storedPoint.z = point.position().z;

            if (point.etherwarpTarget() != null) {
                storedPoint.etherwarpX = point.etherwarpTarget().getX();
                storedPoint.etherwarpY = point.etherwarpTarget().getY();
                storedPoint.etherwarpZ = point.etherwarpTarget().getZ();
            }

            stored.points.add(storedPoint);
        }

        for (BlockPos block : breakerBlocks) {
            StoredBlock storedBlock = new StoredBlock();
            storedBlock.x = block.getX();
            storedBlock.y = block.getY();
            storedBlock.z = block.getZ();

            stored.breakerBlocks.add(storedBlock);
        }

        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(stored), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }

        cache.put(
                CacheKey.of(location, route),
                new SavedRoute(
                        location,
                        route,
                        List.copyOf(points),
                        List.copyOf(breakerBlocks)
                )
        );
    }

    public void delete(RouteLocation location, RouteKey route) throws IOException {
        Path file = findRouteFile(location, route);

        if (file != null) {
            Path parent = file.getParent();
            Files.deleteIfExists(file);
            removeDirectoryIfEmpty(parent);
        }

        cache.keySet().removeIf(key -> key.matches(location, route));
    }

    public void deleteConnection(RouteLocation location) throws IOException {
        String roomId = location.room().id();
        cache.keySet().removeIf(key -> key.matchesConnection(location));
        deleteTree(getConnectionDirectory(location));
        removeDirectoryIfEmpty(root.resolve(safe(roomId)));
    }

    public void deleteRoom(String roomId) throws IOException {
        cache.keySet().removeIf(key -> key.roomId.equals(roomId));
        deleteTree(root.resolve(safe(roomId)));
    }

    private SavedRoute loadFromDisk(RouteLocation location, RouteKey route) {
        Path file = findRouteFile(location, route);

        if (file == null) {
            return new SavedRoute(
                    location,
                    route,
                    List.of(),
                    List.of()
            );
        }

        try {
            StoredRoute stored = GSON.fromJson(
                    Files.readString(file, StandardCharsets.UTF_8),
                    StoredRoute.class
            );

            if (stored == null || stored.points == null) {
                return new SavedRoute(
                        location,
                        route,
                        List.of(),
                        List.of()
                );
            }

            List<RouteNode> nodes = new ArrayList<>(stored.points.size());

            for (StoredPoint point : stored.points) {
                Vec3 position = new Vec3(
                        point.x,
                        point.y,
                        point.z
                );

                if (point.etherwarpX != null
                        && point.etherwarpY != null
                        && point.etherwarpZ != null) {
                    nodes.add(RouteNode.etherwarp(
                            position,
                            new BlockPos(
                                    point.etherwarpX,
                                    point.etherwarpY,
                                    point.etherwarpZ
                            )
                    ));
                } else {
                    nodes.add(RouteNode.normal(position));
                }
            }

            List<BlockPos> breakerBlocks = new ArrayList<>();

            if (stored.breakerBlocks != null) {
                for (StoredBlock block : stored.breakerBlocks) {
                    breakerBlocks.add(new BlockPos(
                            block.x,
                            block.y,
                            block.z
                    ));
                }
            }

            return new SavedRoute(
                    location,
                    route,
                    List.copyOf(nodes),
                    List.copyOf(breakerBlocks)
            );

        } catch (Exception exception) {
            exception.printStackTrace();

            return new SavedRoute(
                    location,
                    route,
                    List.of(),
                    List.of()
            );
        }
    }

    public List<SavedRoute> getAllForRoom(RoomInfo room) {
        Path roomDirectory = root.resolve(safe(room.id()));
        if (!Files.isDirectory(roomDirectory)) return List.of();

        List<SavedRoute> result = new ArrayList<>();

        try (var stream = Files.walk(roomDirectory)) {
            for (Path file : stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".json"))
                    .toList()) {

                try {
                    StoredRoute stored = GSON.fromJson(
                            Files.readString(file, StandardCharsets.UTF_8),
                            StoredRoute.class
                    );

                    if (stored == null
                            || stored.entrance == null
                            || stored.exit == null
                            || stored.route == null
                            || stored.points == null) {
                        continue;
                    }

                    RouteKey key = RouteKey.valueOf(stored.route);
                    RouteLocation location = new RouteLocation(
                            room,
                            stored.entrance,
                            stored.exit
                    );

                    List<RouteNode> nodes = new ArrayList<>();

                    for (StoredPoint point : stored.points) {
                        Vec3 position = new Vec3(point.x, point.y, point.z);

                        if (point.etherwarpX != null
                                && point.etherwarpY != null
                                && point.etherwarpZ != null) {
                            nodes.add(RouteNode.etherwarp(
                                    position,
                                    new BlockPos(
                                            point.etherwarpX,
                                            point.etherwarpY,
                                            point.etherwarpZ
                                    )
                            ));
                        } else {
                            nodes.add(RouteNode.normal(position));
                        }
                    }

                    List<BlockPos> breakerBlocks = new ArrayList<>();

                    if (stored.breakerBlocks != null) {
                        for (StoredBlock block : stored.breakerBlocks) {
                            breakerBlocks.add(new BlockPos(
                                    block.x,
                                    block.y,
                                    block.z
                            ));
                        }
                    }

                    if (nodes.size() >= 2) {
                        result.add(new SavedRoute(
                                location,
                                key,
                                List.copyOf(nodes),
                                List.copyOf(breakerBlocks)
                        ));
                    }
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
            }
        } catch (IOException exception) {
            exception.printStackTrace();
        }

        return List.copyOf(result);
    }

    public record SavedRoute(
            RouteLocation location,
            RouteKey key,
            List<RouteNode> nodes,
            List<BlockPos> breakerBlocks
    ) {
    }

    private Path getFile(RouteLocation location, RouteKey route) {
        return getConnectionDirectory(location).resolve(route.fileName() + ".json");
    }

    private Path findRouteFile(RouteLocation location, RouteKey route) {
        Path exact = getFile(location, route);

        if (Files.isRegularFile(exact)) {
            return exact;
        }

        Path roomDirectory = root.resolve(safe(location.room().id()));

        if (!Files.isDirectory(roomDirectory)) {
            return null;
        }

        Path best = null;
        int bestDistance = Integer.MAX_VALUE;

        try (var stream = Files.walk(roomDirectory)) {
            for (Path candidate : stream
                    .filter(Files::isRegularFile)
                    .filter(path ->
                            path.getFileName()
                                    .toString()
                                    .equalsIgnoreCase(route.fileName() + ".json"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {

                try {
                    StoredRoute stored = GSON.fromJson(
                            Files.readString(candidate, StandardCharsets.UTF_8), StoredRoute.class
                    );

                    if (stored == null
                            || stored.entrance == null
                            || stored.exit == null
                            || stored.route == null
                            || !stored.route.equals(route.name())
                            || !stored.entrance.matchesWithTolerance(location.entrance())
                            || !stored.exit.matchesWithTolerance(location.exit())) {
                        continue;
                    }

                    int distance = stored.entrance.xzDistanceTo(location.entrance())
                                    + stored.exit.xzDistanceTo(location.exit());

                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }

                } catch (Exception exception) {
                    exception.printStackTrace();
                }
            }

        } catch (IOException exception) {
            exception.printStackTrace();
        }

        return best;
    }

    private Path getConnectionDirectory(RouteLocation location) {
        return root
                .resolve(safe(location.room().id()))
                .resolve(location.connectionName());
    }

    private void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        List<Path> paths;
        try (var stream = Files.walk(directory)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }

        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    private void removeDirectoryIfEmpty(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }

        try (var stream = Files.list(directory)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(directory);
            }
        }
    }

    private String safe(String input) {
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record CacheKey(
            String roomId,
            DoorId entrance,
            DoorId exit,
            RouteKey route
    ) {
        static CacheKey of(RouteLocation location, RouteKey route) {
            return new CacheKey(
                    location.room().id(),
                    location.entrance(),
                    location.exit(),
                    route
            );
        }

        boolean matchesConnection(RouteLocation location) {
            return roomId.equals(location.room().id())
                    && entrance.matchesWithTolerance(location.entrance())
                    && exit.matchesWithTolerance(location.exit());
        }

        boolean matches(RouteLocation location, RouteKey routeKey) {
            return route == routeKey && matchesConnection(location);
        }
    }

    private static final class StoredRoute {
        int version;
        String room;
        DoorId entrance;
        DoorId exit;
        String route;
        List<StoredPoint> points = new ArrayList<>();
        List<StoredBlock> breakerBlocks = new ArrayList<>();
    }

    private static final class StoredPoint {
        double x;
        double y;
        double z;

        Integer etherwarpX;
        Integer etherwarpY;
        Integer etherwarpZ;
    }

    private static final class StoredBlock {
        int x;
        int y;
        int z;
    }
}

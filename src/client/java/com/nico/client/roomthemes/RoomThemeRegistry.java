package com.nico.client.roomthemes;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Direction;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

final class RoomThemeRegistry {

    private static final String INDEX_PATH = "/assets/nsm/room_themes/index.json";
    private static final Path USER_DIRECTORY = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("nicos_super_mods")
            .resolve("room_themes");
    private static final Gson GSON = new Gson();

    private RoomThemeRegistry() { }

    static RoomThemeScene[] load() {
        SceneIndex index = read(INDEX_PATH, SceneIndex.class);

        if (index.scenes == null) {
            throw new IllegalArgumentException("Invalid " + INDEX_PATH + ": missing scenes");
        }

       Map<String, RoomThemeScene> byId = new LinkedHashMap<>();

        for (int i = 0; i < index.scenes.length; i++) {
            String path = normalizePath(index.scenes[i]);
            RoomThemeScene scene = read(path, RoomThemeScene.class);
            scene.validate(path);
            scene.source = path;
            byId.put(scene.id, scene);
        }

        loadUserScenes(byId);

        RoomThemeScene[] result = byId.values().toArray(RoomThemeScene[]::new);
        Arrays.sort(result, Comparator.comparing((RoomThemeScene scene) -> scene.priority).reversed());
        return result;
    }

    static Path userDirectory() {
        return USER_DIRECTORY;
    }

    private static void loadUserScenes(Map<String, RoomThemeScene> byId) {
        try {
            Files.createDirectories(USER_DIRECTORY);

            try (Stream<Path> files = Files.walk(USER_DIRECTORY)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(path -> loadUserScene(path, byId));
            }
        } catch (IOException exception) {
            System.err.println("[NSM Room Themes] Could not scan " + USER_DIRECTORY.toAbsolutePath());
            exception.printStackTrace();
        }
    }

    private static void loadUserScene(Path path, Map<String, RoomThemeScene> byId) {
        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(path), StandardCharsets.UTF_8)) {
            RoomThemeScene scene = GSON.fromJson(reader, RoomThemeScene.class);
            if (scene == null) {
                throw new IllegalArgumentException("file is empty");
            }

            scene.validate(path.toString());
            scene.source = path.toAbsolutePath().toString();

            RoomThemeScene replaced = byId.put(scene.id, scene);
            String action = replaced == null ? "Loaded" : "Overrode";
            System.out.println("[NSM Room Themes] " + action + " scene '" + scene.id + "' from " + path.toAbsolutePath());
        } catch (Exception exception) {
            System.err.println("[NSM Room Themes] Ignoring invalid user scene " + path.toAbsolutePath());
            exception.printStackTrace();
        }
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Invalid  " + INDEX_PATH + ": blank scene path");
        }

        return path.startsWith("/") ? path : "/" + path;
    }

    private static <T> T read(String path, Class<T> type) {
        try (InputStream stream = RoomThemeRegistry.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Missing room theme resource " + path);
            }

            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                T result = GSON.fromJson(reader, type);

                if (result == null) {
                    throw new IllegalStateException("Empty room theme resource " + path);
                }

                return result;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read room theme resource" + path, e);
        }
    }

    private static final class SceneIndex {
        String[] scenes;
    }
}

final class RoomThemeScene {
    private static final long MAX_BLOCK_SKIN_VOLUME = 250_000L;

    String id;
    String displayName;
    int priority;
    transient String source;
    Activation activation;
    Sky sky;
    Surface[] surfaces;
    BlockSkin[] blockSkins;
    Template[] templates;
    Instance[] instances;
    Cuboid[] cuboids;
    AnimatedCuboid[] animations;

    private transient Map<String, Template> templatesById;

    void validate(String path) {
        require(id != null && !id.isBlank(), path, "id");
        require(displayName != null && !displayName.isBlank(), path, "displayName");
        require(activation != null, path, "activation");
        require(activation.min != null && activation.min.length == 3, path, "activation.min");
        require(activation.max != null && activation.max.length == 3, path, "activation.max");
        require(activation.masterMode == null
                        || activation.masterMode.equalsIgnoreCase("ANY")
                        || activation.masterMode.equalsIgnoreCase("MASTER")
                        || activation.masterMode.equalsIgnoreCase("NORMAL"),
                path,  "activation.masterMode must be ANY, MASTER, or NORMAL");

        templates = templates == null ? new Template[0] : templates;
        instances = instances == null ? new Instance[0] : instances;
        cuboids = cuboids == null ? new Cuboid[0] : cuboids;
        animations = animations == null ? new AnimatedCuboid[0] : animations;
        surfaces = surfaces == null ? new Surface[0] : surfaces;
        blockSkins = blockSkins == null ? new BlockSkin[0] : blockSkins;
        templatesById = new HashMap<>();

        if (sky != null) {
            require(sky.bands != null && sky.bands.length > 0, path, "sky.bands");
            requireColor(sky.ceilingColor, path, "sky.ceilingColor");

            for (int i = 0; i < sky.bands.length; i++) {
                requireColor(sky.bands[i].color, path, "sky.bands[" + i + "].color");
            }
        }

        for (int i = 0; i < templates.length; i++) {
            Template template = templates[i];
            require(template.id != null && !template.id.isBlank(), path, "templates[" + i + "].id");
            require(template.parts != null, path, "templates[" + i + "].parts");
            require(templatesById.put(template.id, template) == null, path, "duplicate template " + template.id);

            for (int partIndex = 0; partIndex < template.parts.length; partIndex++) {
                Part part = template.parts[partIndex];
                requireVector(part.min, path, "template part min");
                requireVector(part.max, path, "template part max");
                requireColor(part.color, path, "template part color");
            }
        }

        for (int i = 0; i < instances.length; i++) {
            Instance instance = instances[i];
            require(templatesById.containsKey(instance.template), path, "unknown template " + instance.template);
            requireVector(instance.at,  path, "instances[" +  i + "].at");
            requireVector(instance.scale, path, "instances[" +  i + "].scale");
        }

        for (int i = 0; i < cuboids.length; i++) {
            requireVector(cuboids[i].min, path, "cuboids[" +  i + "].min");
            requireVector(cuboids[i].max, path, "cuboids[" +  i + "].max");
            requireColor(cuboids[i].color, path,  "cuboids[" +  i + "].color");
        }

        for (int i = 0; i < surfaces.length; i++) {
            Surface surface = surfaces[i];
            require(surface.vertices != null && surface.vertices.length == 4,
                    path, "surfaces[" + i + "].verticies must contain four points");

            for (int vertex = 0; vertex < surface.vertices.length; vertex++) {
                requireVector(surface.vertices[vertex], path,
                        "surfaces[" + i + "].vertices[" + vertex + "]");
            }

            requireColor(surface.color, path, "surfaces[" + i + "].color");
            require(surface.backingDirection == null || parseDirection(surface.backingDirection) != null,
                    path, "surfaces[" + i + "].backingDirection must be DOWN, UP, NORTH, SOUTH, WEST, or EAST");
        }

        for (int i = 0; i < blockSkins.length; i++) {
            BlockSkin skin = blockSkins[i];
            String name = "blockSkins[" + i + "]";

            requireIntVector(skin.min, path, name + ".min");
            requireIntVector(skin.max, path, name + ".max");
            require(skin.max[0] >= skin.min[0]
                        && skin.max[1] >= skin.min[1]
                        && skin.max[2] >= skin.min[2],
                    path, name + ".max must not be below min");

            long volume = (skin.max[0] - skin.min[0] + 1L)
                    * (skin.max[1] - skin.min[1] + 1L)
                    * (skin.max[2] - skin.min[2] + 1L);
            require(volume <= MAX_BLOCK_SKIN_VOLUME,
                    path, name + " is too large (maximum " + MAX_BLOCK_SKIN_VOLUME + " blocks)");

            require(skin.blocks != null && skin.blocks.length > 0,
                    path, name + ".blocks must contain at least one block id");
            skin.blockIds = new HashSet<>();

            for (String block : skin.blocks) {
                require(block != null && !block.isBlank(), path, name + ".blocks contains a blank id");
                skin.blockIds.add(block.toLowerCase(Locale.ROOT));
            }

            requireColor(skin.topColor, path, name + ".topColor");
            requireColor(skin.sideColor, path, name + ".sideColor");
            requireColor(skin.bottomColor, path, name + ".bottomColor");
            require(skin.minimumDetail >= 0 && skin.minimumDetail <= 2,
                    path, name + ".minimumDetail must be between 0 and 2");
        }

        for (int i = 0; i < animations.length; i++) {
            AnimatedCuboid animation = animations[i];
            requireVector(animation.position, path, "animations[" +  i + "].position");
            requireVector(animation.size, path, "animations[" +  i + "].size");
            requireColor(animation.color, path, "animations[" +  i + "].color");
            require(animation.fallDistance > 0.0, path,  "animations[" +  i + "].fallDistance");
        }
    }

    Template template(String id) {
        return templatesById.get(id);
    }

    private static void requireVector(double[] vector, String path, String name) {
        require(vector != null && vector.length == 3, path, name + " must contain three values");
    }

    private static void requireIntVector(int[] vector, String path, String name) {
        require(vector != null && vector.length == 3, path, name + " must contain three integers");
    }

    private static void requireColor(float[] color, String path, String name) {
        require(color != null && (color.length == 3 || color.length == 4),
                path, name + " must contain RGB or RGBA values");

        for (float channel : color) {
            require(channel >= 0.0f && channel <= 1.0f,
                    path, name + " channels must be between 0 and 1");
        }
    }

    private static void require(boolean condition, String path, String message) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid " + path + ": " + message);
        }
    }

    private static Direction parseDirection(String value) {
        try {
            return Direction.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    static final class Activation {
        boolean requiresSkyblock = true;
        String[] areas;
        int[] floors;
        Boolean boss;
        String masterMode = "ANY";
        double[] min;
        double[] max;
    }

    static final class Sky {
        double minX;
        double maxX;
        double minZ;
        double maxZ;
        double topY;
        float[] ceilingColor;
        SkyBand[] bands;
    }

    static final class SkyBand {
        double minY;
        double maxY;
        float[] color;
    }

    static final class Template {
        String id;
        Part[] parts;
    }

    static final class Part {
        double[] min;
        double[] max;
        float[] color;
        int minimumDetail;
    }

    static final class Instance {
        String template;
        double[] at;
        double[] scale;
        int minimumDetail;
    }

    static final class Cuboid {
        double[] min;
        double[] max;
        float[] color;
        int minimumDetail;
    }

    static final class Surface {
        double[][] vertices;
        float[] color;
        int minimumDetail;
        boolean doubleSided = true;
        String backingDirection;
    }

    static final class BlockSkin {
        int[] min;
        int[] max;
        String[] blocks;
        float[] topColor;
        float[] sideColor;
        float[] bottomColor;
        int minimumDetail;

        private transient Set<String> blockIds = Set.of();

        boolean matches(String blockId) {
            return blockIds.contains(blockId.toLowerCase(Locale.ROOT));
        }

        float[] color(Direction face) {
            if (face == Direction.UP) return topColor;
            if (face == Direction.DOWN) return bottomColor;
            return sideColor;
        }
    }

    static final class AnimatedCuboid {
        double[] position;
        double[] size;
        float[] color;
        double fallDistance;
        double speed;
        double phase;
        double swayFrequency;
        double swayDistance;
        double zSwayMultiplier;
        int minimumDetail;
    }
}

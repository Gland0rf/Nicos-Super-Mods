package com.nico.client.roomthemes;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nico.client.configuration.NsmConfig;
import com.nico.client.configuration.category.CategoryDungeons;
import com.nico.client.dungeon.DungeonState;
import com.nico.client.utils.LocationUtils;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RoomThemeFeature {
    private static RoomThemeScene[] scenes = new RoomThemeScene[0];
    private static RenderState renderState = RenderState.INACTIVE;
    private static RoomThemeScene cachedSurfaceScene;
    private static ClientLevel cachedSurfaceLevel;
    private static RenderedSurface[] cachedSurfaces = new RenderedSurface[0];
    private static boolean initialized;

    private static final double AXIS_EPSILON = 0.0001;
    private static final double SKIN_OFFSET = 0.002;
    private static final int MAX_GENERATED_SKIN_FACES = 30_000;
    private static final Direction[] DIRECTIONS = Direction.values();

    private RoomThemeFeature() { }

    public static void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            scenes = RoomThemeRegistry.load();
            System.out.println("[NSM RoomThemes] Loaded " + scenes.length + " scene(s).");
        } catch (RuntimeException exception) {
            System.err.println("[NSM RoomThemes] Could not load room theme JSON.");
            exception.printStackTrace();
            return;
        }

        LevelRenderEvents.END_EXTRACTION.register(RoomThemeFeature::extract);
        LevelRenderEvents.BEFORE_GIZMOS.register(RoomThemeFeature::render);
    }

    public static synchronized List<String> reloadLines() {
        try {
            RoomThemeScene[] loaded = RoomThemeRegistry.load();
            scenes = loaded;
            renderState = RenderState.INACTIVE;
            clearSurfaceCache();

            return List.of(
                    "§a[NSM Room Themes] Reloaded " + loaded.length + " scene(s).",
                    "§7User themes: §f" + RoomThemeRegistry.userDirectory().toAbsolutePath()
            );
        } catch (RuntimeException exception) {
            System.err.println("[NSM Room Themes] Reload failed; keeping the previous scenes.");
            exception.printStackTrace();
            return List.of(
                    "§c[NSM Room Themes] Reload failed. The previous scenes are still active.",
                    "§7See latest.log for the JSON error."
            );
        }
    }

    public static List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        CategoryDungeons.RoomThemes config = NsmConfig.INSTANCE.dungeons.roomThemes;

        lines.add("§b§lNSM Room Themes §8— §f" + scenes.length + " scene(s) loaded");
        lines.add("§7Feature: " + (config.enabled ? "§aenabled" : "§cdisabled")
                + "§7 | detail: §f" + detailName(config.sceneDetail)
                + "§7 | animations: " + (config.animations ? "§aon" : "§coff"));

        Player player = minecraft.player;

        if (minecraft.level == null || player == null) {
            lines.add("§eJoin a world to inspect scene activation.");
            return lines;
        }

        Integer floor = DungeonState.INSTANCE.getFloorNumber();
        lines.add(String.format(Locale.ROOT,
                "§7Position: §f%.1f, %.1f, %.1f§7 | area: §f%s",
                player.getX(), player.getY(), player.getZ(), LocationUtils.getCurrentArea().name()));
        lines.add("§7SkyBlock: " + yesNo(LocationUtils.isInSkyblock())
                + "§7 | floor: §f" + (floor == null ? "unknown" : floor)
                + "§7 | boss: " + yesNo(DungeonState.INSTANCE.getInBoss())
                + "§7 | mode: §f" + (DungeonState.INSTANCE.getMasterMode() ? "Master" : "Normal"));

        RoomThemeScene active = findScene(player);

        if (active != null) {
            lines.add("§aActive: §f" + active.displayName + " §8(" + active.id + ")");
            lines.add("§7Geometry: §f" + active.surfaces.length + " surfaces, "
                    + active.blockSkins.length + " block skins,"
                    + active.cuboids.length + " cuboids, "
                    + active.instances.length + " instances");
        } else if (scenes.length == 0) {
            lines.add("§cNo scenes loaded. Check latest.log for JSON errors.");
        } else {
            lines.add("§eNo active scene. Closest checks:");

            for (int i = 0; i < Math.min(3, scenes.length); i++) {
                RoomThemeScene scene = scenes[i];
                lines.add("§8 • §f" + scene.displayName + "§7: "
                        + firstMismatch(scene.activation, player));
            }
        }

        lines.add("§7Custom JSON folder: §f" + RoomThemeRegistry.userDirectory().toAbsolutePath());
        return lines;
    }

    private static String detailName(int detail) {
        return switch (Math.max(0, Math.min(2, detail))) {
            case 0 -> "Low";
            case 2 -> "High";
            default -> "Balanced";
        };
    }

    private static String yesNo(boolean value) {
        return value ? "§ayes" : "§cno";
    }

    private static String firstMismatch(RoomThemeScene.Activation activation, Player player) {
        if (activation.requiresSkyblock && !LocationUtils.isInSkyblock()) return "not in skyblock";
        if (!matchesArea(activation.areas)) return "area does not match";
        if (!matchesFloor(activation.floors)) return "floor does not match";
        if (!matchesMasterMode(activation.masterMode)) return "mode does not match";
        if (activation.boss != null && DungeonState.INSTANCE.getInBoss() != activation.boss) return "boss state does not match.";
        if (player.getX() < activation.min[0] || player.getX() > activation.max[0]
                || player.getY() < activation.min[1] || player.getY() > activation.max[1]
                || player.getZ() < activation.min[2] || player.getZ() > activation.max[2]) {
            return "outside activation bounds";
        }
        return "matches; enable the feature if it is disabled";
    }

    private static void extract(LevelExtractionContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        CategoryDungeons.RoomThemes config = NsmConfig.INSTANCE.dungeons.roomThemes;

        if (!config.enabled || minecraft.level == null || player == null) {
            renderState = RenderState.INACTIVE;
            return;
        }

        RoomThemeScene scene = findScene(player);
        if (scene == null) {
            renderState = RenderState.INACTIVE;
            return;
        }

        Vec3 camera = context.camera().position();
        long now = System.currentTimeMillis();

        if (scene != cachedSurfaceScene || minecraft.level != cachedSurfaceLevel) {
            cachedSurfaces = buildSurfaces(scene, minecraft.level);
            cachedSurfaceScene = scene;
            cachedSurfaceLevel = minecraft.level;
        }

        renderState = new RenderState(
                scene,
                cachedSurfaces,
                camera.x,
                camera.y,
                camera.z,
                Math.max(0, Math.min(2, config.sceneDetail)),
                config.animations,
                now
        );
    }

    private static void clearSurfaceCache() {
        cachedSurfaceScene = null;
        cachedSurfaceLevel = null;
        cachedSurfaces = new RenderedSurface[0];
    }

    private static RoomThemeScene findScene(Player player) {
        for (RoomThemeScene scene : scenes) {
            if (matches(scene.activation, player)) {
                return scene;
            }
        }

        return null;
    }

    private static boolean matches(RoomThemeScene.Activation activation, Player player) {
        if (activation.requiresSkyblock && !LocationUtils.isInSkyblock()) return false;
        if (!matchesArea(activation.areas) || !matchesFloor(activation.floors) || !matchesMasterMode(activation.masterMode)) return false;
        if (activation.boss != null && DungeonState.INSTANCE.getInBoss() != activation.boss) return false;

        return player.getX() >= activation.min[0]
                && player.getY() >= activation.min[1]
                && player.getZ() >= activation.min[2]
                && player.getX() <= activation.max[0]
                && player.getY() <= activation.max[1]
                && player.getZ() <= activation.max[2];
    }

    private static boolean matchesArea(String[] areas) {
        if (areas == null || areas.length == 0) return true;

        String current = LocationUtils.getCurrentArea().name();
        for (String area : areas) {
            if (current.equalsIgnoreCase(area)) return true;
        }

        return false;
    }

    private static boolean matchesFloor(int[] floors) {
        if (floors == null || floors.length == 0) return true;

        Integer current = DungeonState.INSTANCE.getFloorNumber();
        if (current == null) return false;

        for (int floor : floors) {
            if (current == floor) return true;
        }

        return false;
    }

    private static boolean matchesMasterMode(String mode) {
        if (mode == null || mode.equalsIgnoreCase("ANY")) return true;

        boolean masterMode = DungeonState.INSTANCE.getMasterMode();
        return mode.equalsIgnoreCase("MASTER") == masterMode;
    }

    private static void render(LevelRenderContext context) {
        RenderState state = renderState;
        if (!state.active()) return;

        PoseStack matrices = context.poseStack();
        MultiBufferSource consumers = context.bufferSource();
        if (matrices == null || consumers == null) return;

        VertexConsumer consumer = consumers.getBuffer(RenderTypes.debugFilledBox());
        PoseStack.Pose pose = matrices.last();

        renderSurfaces(consumer, pose, state);
        renderSky(consumer, pose, state);
        renderCuboids(consumer, pose, state);
        renderInstances(consumer, pose, state);

        if (state.animations()) {
            renderAnimations(consumer, pose, state);
        }
    }

    private static void renderSurfaces(VertexConsumer consumer, PoseStack.Pose pose, RenderState state) {
        for (RenderedSurface surface : state.surfaces()) {
            if (state.detail() < surface.minimumDetail()) continue;

            double[][] vertex = surface.vertices();

            addQuad(consumer, pose,
                    vertex[0][0] - state.cameraX(), vertex[0][1] - state.cameraY(), vertex[0][2] - state.cameraZ(),
                    vertex[1][0] - state.cameraX(), vertex[1][1] - state.cameraY(), vertex[1][2] - state.cameraZ(),
                    vertex[2][0] - state.cameraX(), vertex[2][1] - state.cameraY(), vertex[2][2] - state.cameraZ(),
                    vertex[3][0] - state.cameraX(), vertex[3][1] - state.cameraY(), vertex[3][2] - state.cameraZ(),
                    surface.color());

            if (surface.doubleSided()) {
                addQuad(consumer, pose,
                        vertex[3][0] - state.cameraX(), vertex[3][1] - state.cameraY(), vertex[3][2] - state.cameraZ(),
                        vertex[2][0] - state.cameraX(), vertex[2][1] - state.cameraY(), vertex[2][2] - state.cameraZ(),
                        vertex[1][0] - state.cameraX(), vertex[1][1] - state.cameraY(), vertex[1][2] - state.cameraZ(),
                        vertex[0][0] - state.cameraX(), vertex[0][1] - state.cameraY(), vertex[0][2] - state.cameraZ(),
                        surface.color());
            }
        }
    }

    private static RenderedSurface[] buildSurfaces(RoomThemeScene scene, ClientLevel level) {
        List<RenderedSurface> result = new ArrayList<>();
        long startedAt = System.nanoTime();

        for (RoomThemeScene.Surface surface : scene.surfaces) {
            if (surface.backingDirection == null) {
                result.add(RenderedSurface.from(surface));
                continue;
            }

            Direction backing = Direction.valueOf(surface.backingDirection.toUpperCase(Locale.ROOT));
            clipToOpaqueBlockFaces(surface, backing, level, result);
        }

        int configuredSurfaceCount = result.size();

        for (RoomThemeScene.BlockSkin skin : scene.blockSkins) {
            appendBlockSkin(skin, level, result);

            if (result.size() >= MAX_GENERATED_SKIN_FACES) {
                System.err.println("[NSM Room Themes] Block skin face limit reached for "
                        + scene.id + "; remaining faces were skipped.");
                break;
            }
        }

        int generatedFaces = result.size() - configuredSurfaceCount;
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
        System.out.println("[NSM Room Themes] Built " + generatedFaces
                + " interior skin face(s) for " + scene.id + " in " + elapsedMillis + " ms.");

        return result.toArray(RenderedSurface[]::new);
    }

    private static void appendBlockSkin(
            RoomThemeScene.BlockSkin skin,
            ClientLevel level,
            List<RenderedSurface> output
    ) {
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();

        for (int x = skin.min[0]; x <= skin.max[0]; x++) {
            for (int y = skin.min[1]; y <= skin.max[1]; y++) {
                for (int z = skin.min[2]; z <= skin.max[2]; z++) {
                    if (output.size() >= MAX_GENERATED_SKIN_FACES) return;

                    position.set(x, y, z);
                    BlockState state = level.getBlockState(position);

                    if (!state.canOcclude()) continue;

                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (!skin.matches(blockId)) continue;

                    for (Direction face : DIRECTIONS) {
                        if (level.getBlockState(position.relative(face)).canOcclude()) continue;

                        output.add(new RenderedSurface(
                                blockFace(x, y, z, face),
                                skin.color(face),
                                skin.minimumDetail,
                                true
                        ));

                        if (output.size() >= MAX_GENERATED_SKIN_FACES) return;
                    }
                }
            }
        }
    }

    private static double[][] blockFace(int x, int y, int z, Direction face) {
        double x0 = x;
        double y0 = y;
        double z0 = z;
        double x1 = x + 1.0;
        double y1 = y + 1.0;
        double z1 = z + 1.0;

        return switch (face) {
            case WEST -> new double[][]{
                    {x0 - SKIN_OFFSET, y0, z0}, {x0 - SKIN_OFFSET, y1, z0},
                    {x0 - SKIN_OFFSET, y1, z1}, {x0 - SKIN_OFFSET, y0, z1}
            };
            case EAST -> new double[][]{
                    {x1 + SKIN_OFFSET, y0, z1}, {x1 + SKIN_OFFSET, y1, z1},
                    {x1 + SKIN_OFFSET, y1, z0}, {x1 + SKIN_OFFSET, y0, z0}
            };
            case DOWN -> new double[][]{
                    {x0, y0 - SKIN_OFFSET, z1}, {x1, y0 - SKIN_OFFSET, z1},
                    {x1, y0 - SKIN_OFFSET, z0}, {x0, y0 - SKIN_OFFSET, z0}
            };
            case UP -> new double[][]{
                    {x0, y1 + SKIN_OFFSET, z0}, {x1, y1 + SKIN_OFFSET, z0},
                    {x1, y1 + SKIN_OFFSET, z1}, {x0, y1 + SKIN_OFFSET, z1}
            };
            case NORTH -> new double[][]{
                    {x1, y0, z0 - SKIN_OFFSET}, {x1, y1, z0 - SKIN_OFFSET},
                    {x0, y1, z0 - SKIN_OFFSET}, {x0, y0, z0 - SKIN_OFFSET}
            };
            case SOUTH -> new double[][]{
                    {x0, y0, z1 + SKIN_OFFSET}, {x0, y1, z1 + SKIN_OFFSET},
                    {x1, y1, z1 + SKIN_OFFSET}, {x1, y0, z1 + SKIN_OFFSET}
            };
        };
    }

    private static void clipToOpaqueBlockFaces(
            RoomThemeScene.Surface surface,
            Direction backing,
            ClientLevel level,
            List<RenderedSurface> output
    ) {
        SurfaceAxis axis = SurfaceAxis.of(surface.vertices);

        if (axis == null || axis.directionAxis() != backing.getAxis()) {
            System.err.println("[NSM Room Themes] Surface backingDirection "
                    + surface.backingDirection + " does not match its plane; drawing it without clipping.");
            output.add(RenderedSurface.from(surface));
            return;
        }

        double plane = axis.constant(surface.vertices[0]);

        double minU = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;

        for (double[] vertex : surface.vertices) {
            double u = axis.u(vertex);
            double v = axis.v(vertex);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }

        for (int cellU = (int) Math.floor(minU); cellU < Math.ceil(maxU); cellU++) {
            for (int cellV = (int) Math.floor(minV); cellV < Math.ceil(maxV); cellV++) {
                double u0 = Math.max(minU, cellU);
                double u1 = Math.min(maxU, cellU + 1.0);
                double v0 = Math.max(minV, cellV);
                double v1 = Math.min(maxV, cellV + 1.0);
                double centerU = (u0 + u1) * 0.5;
                double centerV = (v0 + v1) * 0.5;

                if (!pointInsideSurface(centerU, centerV, surface.vertices, axis)) {
                    continue;
                }

                double[] center = axis.point(plane, centerU, centerV);
                BlockPos behind = BlockPos.containing(
                        center[0] + backing.getStepX() * 0.5,
                        center[1] + backing.getStepY() * 0.5,
                        center[2] + backing.getStepZ() * 0.5
                );
                BlockPos inFront = BlockPos.containing(
                        center[0] - backing.getStepX() * 0.5,
                        center[1] - backing.getStepY() * 0.5,
                        center[2] - backing.getStepZ() * 0.5
                );

                if (!level.getBlockState(behind).canOcclude()
                        || !level.getBlockState(inFront).isAir()) {
                    continue;
                }

                output.add(new RenderedSurface(
                        axis.quad(plane, u0, u1, v0, v1),
                        surface.color,
                        surface.minimumDetail,
                        surface.doubleSided
                ));
            }
        }
    }

    private static boolean pointInsideSurface(
        double pointU,
        double pointV,
        double[][] vertices,
        SurfaceAxis axis
    ) {
        boolean inside = false;

        for (int current = 0, previous = vertices.length - 1;
             current < vertices.length;
             previous = current++) {
            double currentU = axis.u(vertices[current]);
            double currentV = axis.v(vertices[current]);
            double previousU = axis.u(vertices[previous]);
            double previousV = axis.v(vertices[previous]);

            if ((currentV > pointV) != (previousV > pointV)
                    && pointU < (previousU - currentU) * (pointV - currentV)
                    / (previousV - currentV) + currentU) {
                inside = !inside;
            }
        }

        return inside;
    }

    private static void renderSky(VertexConsumer consumer, PoseStack.Pose pose, RenderState state) {
        RoomThemeScene.Sky sky = state.scene().sky;
        if (sky == null) return;

        for (RoomThemeScene.SkyBand band : sky.bands) {
            addSkyWall(consumer, pose, state, sky, band, 0);
            addSkyWall(consumer, pose, state, sky, band, 1);
            addSkyWall(consumer, pose, state, sky, band, 2);
            addSkyWall(consumer, pose, state, sky, band, 3);
        }

        addDoubleSidedQuad(consumer, pose, state,
                sky.minX, sky.topY, sky.minZ,
                sky.maxX, sky.topY, sky.minZ,
                sky.maxX, sky.topY, sky.maxZ,
                sky.minX, sky.topY, sky.maxZ,
                sky.ceilingColor);
    }

    private static void addSkyWall(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            RenderState state,
            RoomThemeScene.Sky sky,
            RoomThemeScene.SkyBand band,
            int side
    ) {
        switch (side) {
            case 0 -> addDoubleSidedQuad(consumer, pose, state,
                    sky.minX, band.minY, sky.minZ, sky.maxX, band.minY, sky.minZ,
                    sky.maxX, band.maxY, sky.minZ, sky.minX, band.maxY, sky.minZ, band.color);
            case 1 -> addDoubleSidedQuad(consumer, pose, state,
                    sky.maxX, band.minY, sky.maxZ, sky.minX, band.minY, sky.maxZ,
                    sky.minX, band.maxY, sky.maxZ, sky.maxX, band.maxY, sky.maxZ, band.color);
            case 2 -> addDoubleSidedQuad(consumer, pose, state,
                    sky.minX, band.minY, sky.maxZ, sky.minX, band.minY, sky.minZ,
                    sky.minX, band.maxY, sky.minZ, sky.minX, band.maxY, sky.maxZ, band.color);
            default -> addDoubleSidedQuad(consumer, pose, state,
                    sky.maxX, band.minY, sky.minZ, sky.maxX, band.minY, sky.maxZ,
                    sky.maxX, band.maxY, sky.maxZ, sky.maxX, band.maxY, sky.minZ, band.color);
        }
    }

    private static void renderCuboids(VertexConsumer consumer, PoseStack.Pose pose, RenderState state) {
        for (RoomThemeScene.Cuboid cuboid : state.scene().cuboids) {
            if (state.detail() >= cuboid.minimumDetail) {
                renderBox(consumer, pose, state, cuboid.min, cuboid.max, cuboid.color);
            }
        }
    }

    private static void renderInstances(VertexConsumer consumer, PoseStack.Pose pose, RenderState state) {
        for (RoomThemeScene.Instance instance : state.scene().instances) {
            if (state.detail() < instance.minimumDetail) continue;

            RoomThemeScene.Template template = state.scene().template(instance.template);

            for (RoomThemeScene.Part part : template.parts) {
                if (state.detail() < part.minimumDetail) continue;

                renderBox(
                        consumer, pose, state,
                        instance.at[0] + part.min[0] * instance.scale[0],
                        instance.at[1] + part.min[1] * instance.scale[1],
                        instance.at[2] + part.min[2] * instance.scale[2],
                        instance.at[0] + part.max[0] * instance.scale[0],
                        instance.at[1] + part.max[1] * instance.scale[1],
                        instance.at[2] + part.max[2] * instance.scale[2],
                        part.color
                );
            }
        }
    }

    private static void renderAnimations(VertexConsumer consumer, PoseStack.Pose pose, RenderState state) {
        double time = state.animationMillis() * 0.001;

        for (RoomThemeScene.AnimatedCuboid animation : state.scene().animations) {
            if (state.detail() < animation.minimumDetail) continue;

            double fall = (time * animation.speed + animation.phase) % animation.fallDistance;
            double sway = Math.sin(time * animation.swayFrequency + animation.phase) * animation.swayDistance;
            double minX = animation.position[0] + sway - animation.size[0] * .5;
            double minY = animation.position[1] - fall - animation.size[1] * .5;
            double minZ = animation.position[2] - sway * animation.zSwayMultiplier - animation.size[2] * .5;

            renderBox(
                    consumer, pose, state,
                    minX, minY, minZ,
                    minX + animation.size[0],
                    minY + animation.size[1],
                    minZ + animation.size[2],
                    animation.color
            );
        }
    }

    private static void renderBox(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            RenderState state,
            double[] min,
            double[] max,
            float[] color
    ) {
        renderBox(
                consumer, pose, state,
                min[0], min[1], min[2],
                max[0], max[1], max[2],
                color
        );
    }

    private static void renderBox(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            RenderState state,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            float[] color
    ) {
        double x0 = minX - state.cameraX();
        double y0 = minY - state.cameraY();
        double z0 = minZ - state.cameraZ();
        double x1 = maxX - state.cameraX();
        double y1 = maxY - state.cameraY();
        double z1 = maxZ - state.cameraZ();

        addQuad(consumer, pose, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, color);
        addQuad(consumer, pose, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, color);
        addQuad(consumer, pose, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, color);
        addQuad(consumer, pose, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, color);
        addQuad(consumer, pose, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, color);
        addQuad(consumer, pose, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, color);
    }

    private static void addDoubleSidedQuad(
            VertexConsumer consumer, PoseStack.Pose pose, RenderState state,
            double x0, double y0, double z0, double x1, double y1, double z1,
            double x2, double y2, double z2, double x3, double y3, double z3,
            float[] color
    ) {
        double cameraX = state.cameraX();
        double cameraY = state.cameraY();
        double cameraZ = state.cameraZ();

        addQuad(consumer, pose,
                x0 - cameraX, y0 - cameraY, z0 - cameraZ,
                x1 - cameraX, y1 - cameraY, z1 - cameraZ,
                x2 - cameraX, y2 - cameraY, z2 - cameraZ,
                x3 - cameraX, y3 - cameraY, z3 - cameraZ, color);
        addQuad(consumer, pose,
                x3 - cameraX, y3 - cameraY, z3 - cameraZ,
                x2 - cameraX, y2 - cameraY, z2 - cameraZ,
                x1 - cameraX, y1 - cameraY, z1 - cameraZ,
                x0 - cameraX, y0 - cameraY, z0 - cameraZ, color);
    }

    private static void addQuad(
            VertexConsumer consumer, PoseStack.Pose pose,
            double x0, double y0, double z0, double x1, double y1, double z1,
            double x2, double y2, double z2, double x3, double y3, double z3,
            float[] color
    ) {
        float alpha = color.length == 4 ? color[3] : 1.0f;

        consumer.addVertex(pose, (float) x0, (float) y0, (float) z0).setColor(color[0], color[1], color[2], alpha);
        consumer.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(color[0], color[1], color[2], alpha);
        consumer.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(color[0], color[1], color[2], alpha);
        consumer.addVertex(pose, (float) x3, (float) y3, (float) z3).setColor(color[0], color[1], color[2], alpha);
    }

    private record RenderState(
            RoomThemeScene scene,
            RenderedSurface[] surfaces,
            double cameraX,
            double cameraY,
            double cameraZ,
            int detail,
            boolean animations,
            long animationMillis
    ) {
        private static final RenderState INACTIVE =
                new RenderState(null, new RenderedSurface[0], 0.0, 0.0, 0.0, 0, false, 0L);

        private boolean active() {
            return scene != null;
        }
    }

    private record RenderedSurface(
            double[][] vertices,
            float[] color,
            int minimumDetail,
            boolean doubleSided
    ) {
        private static RenderedSurface from(RoomThemeScene.Surface surface) {
            return new RenderedSurface(
                    surface.vertices,
                    surface.color,
                    surface.minimumDetail,
                    surface.doubleSided
            );
        }
    }

    private enum SurfaceAxis {
        X(Direction.Axis.X) {
            @Override double constant(double[] point) { return point[0]; }
            @Override double u(double[] point) { return point[1]; }
            @Override double v(double[] point) { return point[2]; }
            @Override double[] point(double plane, double u, double v) { return new double[]{plane, u, v}; }
            @Override double[][] quad(double plane, double u0, double u1, double v0, double v1) {
                return new double[][]{{plane, u0, v0}, {plane, u1, v0}, {plane, u1, v1}, {plane, u0, v1}};
            }
        },
        Y(Direction.Axis.Y) {
            @Override double constant(double[] point) { return point[1]; }
            @Override double u(double[] point) { return point[0]; }
            @Override double v(double[] point) { return point[2]; }
            @Override double[] point(double plane, double u, double v) { return new double[]{u, plane, v}; }
            @Override double[][] quad(double plane, double u0, double u1, double v0, double v1) {
                    return new double[][]{{u0, plane, v0}, {u1, plane, v0}, {u1, plane, v1}, {u0, plane, v1}};
            }
        },
        Z(Direction.Axis.Z) {
            @Override double constant(double[] point) { return point[2]; }
            @Override double u(double[] point) { return point[0]; }
            @Override double v(double[] point) { return point[1]; }
            @Override double[] point(double plane, double u, double v) { return new double[]{u, v, plane}; }
            @Override double[][] quad(double plane, double u0, double u1, double v0, double v1) {
                return new double[][]{{u0, v0, plane}, {u1, v0, plane}, {u1, v1, plane}, {u0, v1, plane}};
            }
        };

        private final Direction.Axis directionAxis;

        SurfaceAxis(Direction.Axis directionAxis) {
            this.directionAxis = directionAxis;
        }

        Direction.Axis directionAxis() {
            return directionAxis;
        }

        abstract double constant(double[] point);
        abstract double u(double[] point);
        abstract double v(double[] point);
        abstract double[] point(double plane, double u, double v);
        abstract double[][] quad(double plane, double u0, double u1, double v0, double v1);

        static SurfaceAxis of(double[][] vertices) {
            for (SurfaceAxis axis : values()) {
                double expected = axis.constant(vertices[0]);
                boolean constant = true;

                for (int i = 1; i < vertices.length; i++) {
                    if (Math.abs(axis.constant(vertices[i]) - expected) > AXIS_EPSILON) {
                        constant = false;
                        break;
                    }
                }

                if (constant) return axis;
            }

            return null;
        }
    }
}

package com.nico.client.bloodrush;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class RouteRenderer {

    private static final double FLOOR_OFFSET = 0.035;
    private static final float LINE_WIDTH = 3.0f;

    private static List<PreparedRoute> preparedRoutes = List.of();

    private RouteRenderer() {
    }

    public static void register(RouteEditor editor) {
        LevelRenderEvents.END_EXTRACTION.register(context -> extract(context, editor));
        LevelRenderEvents.BEFORE_GIZMOS.register(RouteRenderer::render);
    }

    private static void extract(LevelExtractionContext context, RouteEditor editor) {
        float partialTick = context.deltaTracker().getGameTimeDeltaPartialTick(false);

        List<RouteEditor.DisplayRoute> routes =
                editor.getDisplayRoutes(partialTick);

        if (routes.isEmpty()) {
            preparedRoutes = List.of();
            return;
        }

        Vec3 camera = context.camera().position();
        List<PreparedRoute> result = new ArrayList<>();

        for (RouteEditor.DisplayRoute route : routes) {
            List<PreparedNode> worldNodes =
                    new ArrayList<>(route.nodes().size());

            for (RouteNode node : route.nodes()) {
                Vec3 worldPosition = RouteTransforms.localToWorld(
                        node.position(),
                        route.location().room()
                );

                BlockPos worldEtherwarpTarget = null;

                if (node.etherwarpTarget() != null) {
                    worldEtherwarpTarget =
                            RouteTransforms.localBlockToWorld(
                                    node.etherwarpTarget(),
                                    route.location().room()
                            );
                }

                worldNodes.add(new PreparedNode(
                        worldPosition,
                        worldEtherwarpTarget
                ));
            }

            List<BlockPos> worldBreakerBlocks = new ArrayList<>();

            for (BlockPos block : route.breakerBlocks()) {
                worldBreakerBlocks.add(
                        RouteTransforms.localBlockToWorld(
                                block,
                                route.location().room()
                        )
                );
            }

            result.add(new PreparedRoute(
                    route.key(),
                    List.copyOf(worldNodes),
                    List.copyOf(worldBreakerBlocks),
                    route.editing(),
                    route.committedPoints(),
                    camera
            ));
        }

        preparedRoutes = List.copyOf(result);
    }

    private static void render(LevelRenderContext context) {
        if (preparedRoutes.isEmpty()) {
            return;
        }

            PoseStack matrices = context.poseStack();
            MultiBufferSource consumers = context.bufferSource();
        if (matrices == null || consumers == null) {
            return;
        }

        VertexConsumer consumer = consumers.getBuffer(RenderTypes.lines());
        PoseStack.Pose pose = matrices.last();

        for (PreparedRoute route : preparedRoutes) {
            for (int i = 0; i < route.nodes().size() - 1; i++) {
                PreparedNode from = route.nodes().get(i);
                PreparedNode to = route.nodes().get(i + 1);

                Vec3 start = from.position();

                if (from.etherwarpTarget() != null) {
                    start = etherwarpLandingPoint(from.etherwarpTarget());
                }

                drawFloorSegment(
                        consumer,
                        pose,
                        route.cameraPosition(),
                        start,
                        to.position(),
                        route.key()
                );
            }

            for (PreparedNode node : route.nodes) {
                if (node.etherwarpTarget() != null) {
                    drawEtherwarpBlock(
                            consumers,
                            pose,
                            route.cameraPosition(),
                            node.etherwarpTarget()
                    );
                }
            }

            for (BlockPos block : route.breakerBlocks()) {
                drawHighlightedBlock(
                        consumers,
                        pose,
                        route.cameraPosition(),
                        block,
                        0.2f,
                        1.0f,
                        0.3f
                );
            }

            if (route.editing()) {
                int count = Math.min(route.committedPoints(), route.nodes().size());
                for (int i = 0; i < count; i++) {
                    drawAnchor(
                            consumer,
                            pose,
                            route.cameraPosition(),
                            route.nodes().get(i).position(),
                            route.key()
                    );
                }
            }
        }
    }

    private static void drawFloorSegment(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            Vec3 camera,
            Vec3 from,
            Vec3 to,
            RouteKey key
    ) {
        drawSegment(
                consumer,
                pose,
                camera,
                from.add(0, FLOOR_OFFSET, 0),
                to.add(0, FLOOR_OFFSET, 0),
                key
        );
    }

    private static void drawAnchor(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            Vec3 camera,
            Vec3 position,
            RouteKey key
    ) {
        double size = 0.18;

        Vec3 x1 = position.add(-size, FLOOR_OFFSET, 0);
        Vec3 x2 = position.add(size, FLOOR_OFFSET, 0);
        Vec3 z1 = position.add(0, FLOOR_OFFSET, -size);
        Vec3 z2 = position.add(0, FLOOR_OFFSET, size);

        drawSegment(consumer, pose, camera, x1, x2, key);
        drawSegment(consumer, pose, camera, z1, z2, key);
    }

    private static void drawSegment(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            Vec3 camera,
            Vec3 from,
            Vec3 to,
            RouteKey key
    ) {
        Vec3 direction = to.subtract(from);
        if (direction.lengthSqr() < 0.000001) {
            return;
        }
        direction = direction.normalize();

        Vec3 a = from.subtract(camera);
        Vec3 b = to.subtract(camera);

        consumer.addVertex(pose, (float) a.x, (float) a.y, (float) a.z)
                .setColor(key.red(), key.green(), key.blue(), 255)
                .setNormal(pose, (float) direction.x, (float) direction.y, (float) direction.z)
                .setLineWidth(LINE_WIDTH);

        consumer.addVertex(pose, (float) b.x, (float) b.y, (float) b.z)
                .setColor(key.red(), key.green(), key.blue(), 255)
                .setNormal(pose, (float) direction.x, (float) direction.y, (float) direction.z)
                .setLineWidth(LINE_WIDTH);
    }

    private static void drawEtherwarpBlock(
            MultiBufferSource consumers,
            PoseStack.Pose pose,
            Vec3 camera,
            BlockPos block
    ) {
        drawHighlightedBlock(
                consumers,
                pose,
                camera,
                block,
                0.25f,
                0.65f,
                1.0f
        );
    }

    private static void drawBoxOutline(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        drawLine(consumer, pose, minX, minY, minZ, maxX, minY, minZ, red, green, blue, alpha);
        drawLine(consumer, pose, maxX, minY, minZ, maxX, minY, maxZ, red, green, blue, alpha);
        drawLine(consumer, pose, maxX, minY, maxZ, minX, minY, maxZ, red, green, blue, alpha);
        drawLine(consumer, pose, minX, minY, maxZ, minX, minY, minZ, red, green, blue, alpha);

        drawLine(consumer, pose, minX, maxY, minZ, maxX, maxY, minZ, red, green, blue, alpha);
        drawLine(consumer, pose, maxX, maxY, minZ, maxX, maxY, maxZ, red, green, blue, alpha);
        drawLine(consumer, pose, maxX, maxY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);
        drawLine(consumer, pose, minX, maxY, maxZ, minX, maxY, minZ, red, green, blue, alpha);

        drawLine(consumer, pose, minX, minY, minZ, minX, maxY, minZ, red, green, blue, alpha);
        drawLine(consumer, pose, maxX, minY, minZ, maxX, maxY, minZ, red, green, blue, alpha);
        drawLine(consumer, pose, maxX, minY, maxZ, maxX, maxY, maxZ, red, green, blue, alpha);
        drawLine(consumer, pose, minX, minY, maxZ, minX, maxY, maxZ, red, green, blue, alpha);
    }

    private static void drawLine(
            VertexConsumer consumer,
            PoseStack.Pose pose,
            double x1,
            double y1,
            double z1,
            double x2,
            double y2,
            double z2,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        Vec3 direction = new Vec3(x2 - x1, y2 - y1, z2 - z1).normalize();

        consumer.addVertex(pose.pose(), (float) x1, (float) y1, (float) z1)
                .setColor(red, green, blue, alpha)
                .setNormal(pose, (float) direction.x, (float) direction.y, (float) direction.z)
                .setLineWidth(2.0f);

        consumer.addVertex(pose.pose(), (float) x2, (float) y2, (float) z2)
                .setColor(red, green, blue, alpha)
                .setNormal(pose, (float) direction.x, (float) direction.y, (float) direction.z)
                .setLineWidth(2.0f);
    }

    private static void drawHighlightedBlock(
            MultiBufferSource consumers,
            PoseStack.Pose pose,
            Vec3 camera,
            BlockPos block,
            float red,
            float green,
            float blue
    ) {
        double expand = 0.003;

        double minX = block.getX() - camera.x - expand;
        double minY = block.getY() - camera.y - expand;
        double minZ = block.getZ() - camera.z - expand;
        double maxX = block.getX() + 1.0 - camera.x + expand;
        double maxY = block.getY() + 1.0 - camera.y + expand;
        double maxZ = block.getZ() + 1.0 - camera.z + expand;

        VertexConsumer outline = consumers.getBuffer(RenderTypes.lines());

        drawBoxOutline(
                outline,
                pose,
                minX, minY, minZ,
                maxX, maxY, maxZ,
                red, green, blue, 1.0f
        );
    }

    private static Vec3 etherwarpLandingPoint(BlockPos target) {
        return new Vec3(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
    }

    private record PreparedRoute(
            RouteKey key,
            List<PreparedNode> nodes,
            List<BlockPos> breakerBlocks,
            boolean editing,
            int committedPoints,
            Vec3 cameraPosition
    ) {
    }

    private record PreparedNode(
            Vec3 position,
            BlockPos etherwarpTarget
    ) {
    }
}

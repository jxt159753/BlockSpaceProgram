package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.ParachuteDeployment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

final class SableParachuteRenderer {
    private static final RenderType CANOPY = RenderType.entityCutoutNoCull(
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "textures/entity/parachute_canopy.png"));
    private static final Vec3 UP = new Vec3(0, 1, 0);
    private static final Vec3 EAST = new Vec3(1, 0, 0);
    private static final int SIDES = 16;
    private static final int BANDS = 3;
    private static final double DOME_HEIGHT = 0.45;

    private SableParachuteRenderer() {}

    static void render(RenderLevelStageEvent event) {
        var game = Minecraft.getInstance();
        var level = game.level;
        if (level == null) {
            return;
        }
        var container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        var buffers = game.renderBuffers().bufferSource();
        var vertices = buffers.getBuffer(CANOPY);
        var pose = event.getPoseStack().last();
        var camera = event.getCamera().getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for (var entity : level.entitiesForRendering()) {
            if (!(entity instanceof AssembledShipEntity ship) || !ship.isFlyingShip()) {
                continue;
            }
            byte[] states = ship.parachuteStates();
            var design = ship.design();
            var plot = container.getPlot(ship.chunkPosition());
            if (states.length == 0 || design == null || plot == null || !(plot.getSubLevel() instanceof ClientSubLevel subLevel)) {
                continue;
            }
            // renderPose reuses its internal pose when called with another partial tick.
            var previous = new Pose3d(subLevel.renderPose(0));
            var current = new Pose3d(subLevel.renderPose(1));
            var interpolated = subLevel.renderPose(partialTick);
            var origin = Vec3.atLowerCornerOf(ship.blockPosition());
            int index = 0;
            for (var node : design.nodes()) {
                if (node.part() == null) {
                    continue;
                }
                for (var parachute : node.part().parachutes()) {
                    byte state = index < states.length ? states[index] : 0;
                    index++;
                    double area = ParachuteDeployment.dragArea(parachute.performance(), state);
                    if (area == 0) {
                        continue;
                    }
                    Vec3 local = origin.add(node.point(parachute.center()));
                    Vec3 anchor = interpolated.transformPosition(local);
                    Vec3 direction = previous.transformPosition(local).subtract(current.transformPosition(local)).normalize();
                    if (direction.lengthSqr() == 0) {
                        direction = UP;
                    }
                    double radius = Math.sqrt(area / Math.PI);
                    Vec3 rim = anchor.add(direction.scale(radius * 1.6));
                    if (event.getFrustum().isVisible(new AABB(anchor, rim).inflate(radius))) {
                        int light = LevelRenderer.getLightColor(level, BlockPos.containing(anchor));
                        draw(vertices, pose, anchor.subtract(camera), rim.subtract(camera), direction, radius, light);
                    }
                }
            }
        }
        buffers.endBatch(CANOPY);
    }

    private static void draw(VertexConsumer vertices, PoseStack.Pose pose, Vec3 anchor, Vec3 rim, Vec3 axis,
            double radius, int light) {
        Vec3 side = (Math.abs(axis.y) > 0.9 ? EAST : UP).cross(axis).normalize();
        Vec3 across = axis.cross(side);
        for (int segment = 0; segment < SIDES; segment++) {
            double angle = 2 * Math.PI * segment / SIDES;
            double next = 2 * Math.PI * (segment + 1) / SIDES;
            Vec3 radial = side.scale(Math.cos(angle)).add(across.scale(Math.sin(angle)));
            Vec3 following = side.scale(Math.cos(next)).add(across.scale(Math.sin(next)));
            for (int band = 0; band < BANDS; band++) {
                double top = Math.PI * band / (2 * BANDS);
                double bottom = Math.PI * (band + 1) / (2 * BANDS);
                fabric(vertices, pose, rim, axis, radial, radius, angle, top, light);
                fabric(vertices, pose, rim, axis, radial, radius, angle, bottom, light);
                fabric(vertices, pose, rim, axis, following, radius, next, bottom, light);
                fabric(vertices, pose, rim, axis, following, radius, next, top, light);
            }
            if (segment % 2 == 0) {
                Vec3 edge = rim.add(radial.scale(radius));
                Vec3 tangent = axis.cross(radial);
                Vec3 line = edge.subtract(anchor).normalize();
                double width = Math.clamp(radius * 0.008, 0.012, 0.04);
                cord(vertices, pose, anchor, edge, tangent.scale(width), line.cross(tangent), light);
                cord(vertices, pose, anchor, edge, line.cross(tangent).scale(width), tangent, light);
            }
        }
    }

    private static void fabric(VertexConsumer vertices, PoseStack.Pose pose, Vec3 rim, Vec3 axis, Vec3 radial,
            double radius, double angle, double latitude, int light) {
        double run = Math.sin(latitude);
        double rise = Math.cos(latitude);
        Vec3 point = rim.add(radial.scale(radius * run)).add(axis.scale(radius * DOME_HEIGHT * rise));
        Vec3 normal = radial.scale(run).add(axis.scale(rise / DOME_HEIGHT)).normalize();
        float u = (float) (0.5 + 0.46 * run * Math.cos(angle));
        float v = (float) (0.5 + 0.46 * run * Math.sin(angle));
        vertex(vertices, pose, point, normal, u, v, light);
    }

    private static void cord(VertexConsumer vertices, PoseStack.Pose pose, Vec3 anchor, Vec3 edge, Vec3 width,
            Vec3 normal, int light) {
        vertex(vertices, pose, anchor.subtract(width), normal, 0.025F, 0.025F, light);
        vertex(vertices, pose, anchor.add(width), normal, 0.025F, 0.025F, light);
        vertex(vertices, pose, edge.add(width), normal, 0.025F, 0.025F, light);
        vertex(vertices, pose, edge.subtract(width), normal, 0.025F, 0.025F, light);
    }

    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose, Vec3 point, Vec3 normal, float u, float v, int light) {
        vertices.addVertex(pose, (float) point.x, (float) point.y, (float) point.z).setColor(0xFFFFFFFF)
                .setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }
}

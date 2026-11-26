package io.github.jxt159753.blockspaceprogram.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionf;

final class SableCollisionDebugRenderer {
    private SableCollisionDebugRenderer() {}

    static void render(RenderLevelStageEvent event) {
        var game = Minecraft.getInstance();
        var container = SubLevelContainer.getContainer(game.level);
        if (container == null) {
            return;
        }
        var buffers = game.renderBuffers().bufferSource();
        var lines = buffers.getBuffer(RenderType.lines());
        var poses = event.getPoseStack();
        var camera = event.getCamera().getPosition();
        var collisionBlock = BlockSpaceProgram.SHIP_COLLISION_CELL.get();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        for (var subLevel : container.getAllSubLevels()) {
            if (!event.getFrustum().isVisible(subLevel.boundingBox().toMojang())) {
                continue;
            }
            var plot = subLevel.getPlot();
            var origin = plot.getCenterBlock();
            var pose = subLevel.renderPose(partialTick);
            var offset = pose.transformPosition(Vec3.atLowerCornerOf(origin)).subtract(camera);
            var scale = pose.scale();
            poses.pushPose();
            poses.translate(offset.x, offset.y, offset.z);
            poses.mulPose(new Quaternionf(pose.orientation()));
            poses.scale((float) scale.x(), (float) scale.y(), (float) scale.z());
            for (var chunk : plot.getLoadedChunks()) {
                chunk.getChunk().findBlocks(state -> state.is(collisionBlock), (pos, state) -> {
                    var bounds = state.getCollisionShape(game.level, pos).bounds();
                    LevelRenderer.renderLineBox(poses, lines, bounds.move(pos.subtract(origin)),
                            1.0F, 0.55F, 0.1F, 1.0F);
                });
            }
            poses.popPose();
        }
        buffers.endBatch(RenderType.lines());
    }
}

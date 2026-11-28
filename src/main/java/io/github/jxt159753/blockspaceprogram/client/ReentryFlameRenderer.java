package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3f;
import org.joml.Vector3fc;

final class ReentryFlameRenderer {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID,
            "textures/effect/reentry_flame.png");
    private static final RenderType TRANSLUCENT = RenderType.entityTranslucentEmissive(TEXTURE, false);
    private static final RenderType CUTOUT = RenderType.entityCutoutNoCull(TEXTURE);
    private static final int SIDES = 20;
    private static final float[] RADIUS = {0.08F, 0.67F, 1.12F, 1.26F};
    private static final float[] V = {0, 0.15F, 0.38F, 1};

    private ReentryFlameRenderer() {}

    static void render(AssembledShipEntity ship, AABB bounds, float partialTick, PoseStack poses, MultiBufferSource buffers) {
        var wind = ship.reentryWindward();
        if (wind.lengthSquared() == 0) {
            return;
        }
        var reference = Math.abs(wind.y()) > 0.95F ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
        var span = new Vector3f(wind).cross(reference).normalize();
        var rise = new Vector3f(wind).cross(span).normalize();
        var center = bounds.getCenter().toVector3f();
        float depth = extent(bounds, wind);
        float side = extent(bounds, span) + 0.22F;
        float up = extent(bounds, rise) + 0.22F;
        float[] offset = {depth + 1.10F, depth + 0.72F, depth + 0.23F, -depth - 2.1F};
        float phase = (ship.tickCount + partialTick) * 0.045F;
        int alpha = Math.round(255 * (0.35F + 0.65F * Mth.clamp(ship.reentryHeatRatio(), 0, 1)));
        // Shader packs can lose the emissive translucent silhouette against the sky.
        var buffer = buffers.getBuffer(BlockSpaceProgram.isIrisShaderPackInUse() ? CUTOUT : TRANSLUCENT);
        var pose = poses.last();
        var previous = ringOffset(span, rise, side, up, 0, phase);
        for (int face = 0; face < SIDES; face++) {
            float u = (float) face / SIDES;
            float nextU = (float) (face + 1) / SIDES;
            var next = ringOffset(span, rise, side, up, nextU, phase);
            for (int ring = 0; ring < RADIUS.length - 1; ring++) {
                vertex(buffer, pose, center, wind, previous, offset[ring], ring, u, alpha);
                vertex(buffer, pose, center, wind, next, offset[ring], ring, nextU, alpha);
                vertex(buffer, pose, center, wind, next, offset[ring + 1], ring + 1, nextU, alpha);
                vertex(buffer, pose, center, wind, previous, offset[ring + 1], ring + 1, u, alpha);
            }
            previous = next;
        }
    }

    private static float extent(AABB bounds, Vector3fc axis) {
        return (float) ((Math.abs(axis.x()) * bounds.getXsize() + Math.abs(axis.y()) * bounds.getYsize()
                + Math.abs(axis.z()) * bounds.getZsize()) * 0.5);
    }

    private static Vector3f ringOffset(Vector3fc span, Vector3fc rise, float side, float up, float u, float phase) {
        float angle = u * Mth.TWO_PI;
        float ripple = 1 + 0.055F * Mth.sin(angle * 5 - phase) + 0.028F * Mth.sin(angle * 9 + 2 * phase);
        return new Vector3f(span).mul(ripple * side * Mth.cos(angle)).fma(ripple * up * Mth.sin(angle), rise);
    }

    private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, Vector3fc center, Vector3fc wind,
            Vector3fc radial, float depth, int ring, float u, int alpha) {
        float r = RADIUS[ring];
        buffer.addVertex(pose, center.x() + depth * wind.x() + r * radial.x(),
                center.y() + depth * wind.y() + r * radial.y(), center.z() + depth * wind.z() + r * radial.z())
                .setColor(255, 255, 255, alpha).setUv(u, V[ring]).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(pose, wind.x(), wind.y(), wind.z());
    }
}

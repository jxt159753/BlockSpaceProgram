package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;
import org.joml.Vector3fc;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class ReentryFlameClient {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID,
            "textures/effect/reentry_flame.png");
    private static final int SIDES = 20;
    private static final float[] RADIUS = {0.08F, 0.67F, 1.12F, 1.26F};
    private static final float[] V = {0, 0.15F, 0.38F, 1};
    private static final String CAPTURE = System.getProperty("bsp.reentryHeating.capture", "");
    private static int renderedTick = -100;
    private static int visibleTicks;
    private static int shotTicks;
    private static boolean captured;

    private ReentryFlameClient() {}

    static void render(AssembledShipEntity ship, AABB bounds, PoseStack poses, MultiBufferSource buffers) {
        Vector3fc wind = ship.reentryWindward();
        float strength = Mth.clamp((ship.reentryHeatRatio() - 0.38F) * 1.7F, 0, 1);
        if (strength <= 0 || wind.lengthSquared() == 0) {
            return;
        }
        var span = new Vector3f(wind).cross(0, Math.abs(wind.y()) > 0.95F ? 0 : 1,
                Math.abs(wind.y()) > 0.95F ? 1 : 0).normalize();
        var rise = new Vector3f(wind).cross(span).normalize();
        var center = bounds.getCenter().toVector3f();
        float depth = extent(bounds, wind);
        float side = extent(bounds, span) + 0.22F;
        float up = extent(bounds, rise) + 0.22F;
        float[] offset = {depth + 1.10F, depth + 0.72F, depth + 0.23F, -depth - 2.1F};
        float phase = ship.tickCount * 0.045F;
        int alpha = Math.round(255 * strength);
        var buffer = buffers.getBuffer(RenderType.entityTranslucentEmissive(TEXTURE, false));
        for (int face = 0; face < SIDES; face++) {
            float u = (float) face / SIDES;
            float nextU = (float) (face + 1) / SIDES;
            var a = ringOffset(span, rise, side, up, u, phase);
            var b = ringOffset(span, rise, side, up, nextU, phase);
            for (int ring = 0; ring < RADIUS.length - 1; ring++) {
                vertex(buffer, poses.last(), center, wind, a, offset[ring], ring, u, alpha);
                vertex(buffer, poses.last(), center, wind, b, offset[ring], ring, nextU, alpha);
                vertex(buffer, poses.last(), center, wind, b, offset[ring + 1], ring + 1, nextU, alpha);
                vertex(buffer, poses.last(), center, wind, a, offset[ring + 1], ring + 1, u, alpha);
            }
        }
        if (!CAPTURE.isEmpty() && Minecraft.getInstance().player != null) {
            renderedTick = Minecraft.getInstance().player.tickCount;
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

    @SubscribeEvent
    public static void capture(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        if (CAPTURE.isEmpty() || game.player == null || captured || game.player.getY() < 165) {
            return;
        }
        visibleTicks = game.player.tickCount - renderedTick <= 2 ? visibleTicks + 1 : 0;
        shotTicks++;
        if ((shotTicks >= 155 && visibleTicks >= 30) || shotTicks == 240) {
            captured = true;
            String name = visibleTicks >= 30 ? CAPTURE : CAPTURE + "-scene";
            Screenshot.grab(game.gameDirectory, name + ".png", game.getMainRenderTarget(),
                    message -> game.execute(game::stop));
        }
    }
}

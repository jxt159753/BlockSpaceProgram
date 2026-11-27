package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

final class PlanetStarRenderer {
    private static final double SKY_DISTANCE = 100;
    private static VertexBuffer stars;

    private PlanetStarRenderer() {}

    static void draw(Matrix4f view, Matrix4f projection, float brightness) {
        if (stars == null) {
            stars = build();
        }
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        RenderSystem.setShaderColor(brightness, brightness, brightness, 1);
        stars.bind();
        stars.drawWithShader(view, projection, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        RenderSystem.setShaderColor(1, 1, 1, 1);
    }

    static void close() {
        if (stars != null) {
            stars.close();
            stars = null;
        }
    }

    private static VertexBuffer build() {
        var builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (var star : PlanetStarField.generate(PlanetStarField.SEED)) {
            var direction = new Vec3(star.x(), star.y(), star.z());
            var tangent = new Vec3(-star.y(), star.x(), 0).normalize();
            var bitangent = direction.cross(tangent);
            var right = tangent.scale(Math.cos(star.roll())).add(bitangent.scale(Math.sin(star.roll())))
                    .scale(star.halfWidth());
            var up = direction.cross(right);
            var center = direction.scale(SKY_DISTANCE);
            float brightness = star.brightness();
            float red = brightness * (1 - 0.17F * Math.max(0, -star.warmth()));
            float green = brightness * (1 - 0.08F * Math.abs(star.warmth()));
            float blue = brightness * (1 - 0.20F * Math.max(0, star.warmth()));
            vertex(builder, center.subtract(right).subtract(up), red, green, blue);
            vertex(builder, center.subtract(right).add(up), red, green, blue);
            vertex(builder, center.add(right).add(up), red, green, blue);
            vertex(builder, center.add(right).subtract(up), red, green, blue);
        }
        var buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(builder.buildOrThrow());
        VertexBuffer.unbind();
        return buffer;
    }

    private static void vertex(BufferBuilder builder, Vec3 point, float red, float green, float blue) {
        builder.addVertex((float) point.x, (float) point.y, (float) point.z).setColor(red, green, blue, 1);
    }
}

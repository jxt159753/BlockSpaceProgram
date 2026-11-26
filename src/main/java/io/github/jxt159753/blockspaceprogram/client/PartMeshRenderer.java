package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.FastColor;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

public final class PartMeshRenderer {
    private static final int CACHE_BUDGET = 16 * 1024 * 1024;
    private static final VertexFormat FORMAT = DefaultVertexFormat.NEW_ENTITY;
    private static final int STRIDE = FORMAT.getVertexSize();
    private static final int COLOR_OFFSET = FORMAT.getOffset(VertexFormatElement.COLOR);
    private static final int UV_OFFSET = FORMAT.getOffset(VertexFormatElement.UV0);
    private static final int LIGHT_OFFSET = FORMAT.getOffset(VertexFormatElement.UV2);
    private static final int NORMAL_OFFSET = FORMAT.getOffset(VertexFormatElement.NORMAL);

    private static final Map<BakedPart.Geometry, Mesh> MESHES = new LinkedHashMap<>(16, 0.75F, true);
    private static long cachedBytes;
    private static ShaderInstance cutoutShader;

    private PartMeshRenderer() {
    }

    /** Draws an installed part. The caller positions and orients the pose at the part's minimum corner. */
    public static void render(BakedPart part, PoseStack poses, MultiBufferSource buffers, int light) {
        Mesh mesh = getMesh(part.geometry());
        poses.pushPose();
        poses.scale(part.scale(), part.scale(), part.scale());
        if (mesh.cutoutBuffer() != null) {
            drawCutout(mesh.cutoutBuffer(), poses.last(), light);
        } else {
            drawBuffered(mesh.cutout(), Sheets.cutoutBlockSheet(), poses.last(), buffers, light, OverlayTexture.NO_OVERLAY);
        }
        drawBuffered(mesh.translucent(), Sheets.translucentCullBlockSheet(), poses.last(), buffers, light, OverlayTexture.NO_OVERLAY);
        poses.popPose();
    }

    static void renderItem(BakedPart part, PoseStack poses, MultiBufferSource buffers, int light, int overlay) {
        Mesh mesh = getMesh(part.geometry());
        var size = part.modelToLocal(part.geometry().size());
        float fit = (float) (1.0 / Math.max(1.0, Math.max(size.x, Math.max(size.y, size.z))));
        poses.pushPose();
        poses.translate((1 - size.x * fit) / 2, (1 - size.y * fit) / 2, (1 - size.z * fit) / 2);
        float previewScale = fit * part.scale();
        poses.scale(previewScale, previewScale, previewScale);
        drawBuffered(mesh.cutout(), Sheets.cutoutBlockSheet(), poses.last(), buffers, light, overlay);
        drawBuffered(mesh.translucent(), Sheets.translucentCullBlockSheet(), poses.last(), buffers, light, overlay);
        poses.popPose();
    }

    static void setCutoutShader(ShaderInstance shader) {
        cutoutShader = shader;
    }

    static void clear() {
        MESHES.values().forEach(Mesh::close);
        MESHES.clear();
        cachedBytes = 0;
    }

    private static Mesh getMesh(BakedPart.Geometry geometry) {
        Mesh mesh = MESHES.get(geometry);
        if (mesh != null) {
            return mesh;
        }

        mesh = bake(geometry);
        if (mesh.bytes() <= CACHE_BUDGET) {
            var oldest = MESHES.values().iterator();
            while (cachedBytes + mesh.bytes() > CACHE_BUDGET) {
                Mesh evicted = oldest.next();
                oldest.remove();
                cachedBytes -= evicted.bytes();
                evicted.close();
            }
            MESHES.put(geometry, mesh);
            cachedBytes += mesh.bytes();
        }
        return mesh;
    }

    private static Mesh bake(BakedPart.Geometry geometry) {
        var dispatcher = Minecraft.getInstance().getBlockRenderer();
        var view = new PartBlockView(geometry);
        PoseStack poses = new PoseStack();
        RandomSource random = RandomSource.create();
        try (ByteBufferBuilder cutoutStorage = new ByteBufferBuilder(4096);
                ByteBufferBuilder translucentStorage = new ByteBufferBuilder(4096)) {
            var cutout = new BufferBuilder(cutoutStorage, VertexFormat.Mode.QUADS, FORMAT);
            var translucent = new BufferBuilder(translucentStorage, VertexFormat.Mode.QUADS, FORMAT);
            for (BakedPart.StructureBlock block : geometry.blocks()) {
                var state = block.state();
                if (state.getRenderShape() != RenderShape.MODEL) {
                    continue;
                }
                var pos = block.pos();
                var model = dispatcher.getBlockModel(state);
                var data = model.getModelData(view, pos, state, ModelData.EMPTY);
                random.setSeed(state.getSeed(pos));
                poses.pushPose();
                poses.translate(pos.getX(), pos.getY(), pos.getZ());
                for (RenderType layer : model.getRenderTypes(state, random, data)) {
                    var target = layer == RenderType.translucent() ? translucent : cutout;
                    dispatcher.renderBatched(state, pos, view, poses, target, true, random, data, layer);
                }
                poses.popPose();
            }

            try (MeshData cutoutData = cutout.build(); MeshData translucentData = translucent.build()) {
                byte[] cutoutVertices = copyVertices(cutoutData);
                byte[] translucentVertices = copyVertices(translucentData);
                // Count retained vertex bytes on both sides of the upload and allow for each cache key and buffer object.
                long bytes = (long) cutoutVertices.length + translucentVertices.length + 512 + 64L * geometry.blocks().size();
                VertexBuffer cutoutBuffer = null;
                if (cutoutData != null && bytes + cutoutVertices.length <= CACHE_BUDGET) {
                    cutoutBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    cutoutBuffer.bind();
                    try {
                        cutoutBuffer.upload(cutoutData);
                    } catch (RuntimeException e) {
                        cutoutBuffer.close();
                        throw e;
                    } finally {
                        VertexBuffer.unbind();
                    }
                    bytes += cutoutVertices.length;
                }
                return new Mesh(cutoutVertices, translucentVertices, cutoutBuffer, bytes);
            }
        }
    }

    private static byte[] copyVertices(@Nullable MeshData data) {
        if (data == null) {
            return new byte[0];
        }
        ByteBuffer buffer = data.vertexBuffer();
        byte[] vertices = new byte[buffer.remaining()];
        buffer.get(vertices);
        return vertices;
    }

    private static void drawCutout(VertexBuffer buffer, PoseStack.Pose pose, int light) {
        RenderType type = Sheets.cutoutBlockSheet();
        type.setupRenderState();
        try {
            cutoutShader.safeGetUniform("PartPose").set(pose.pose());
            cutoutShader.safeGetUniform("PartNormal").set(pose.normal());
            cutoutShader.safeGetUniform("PartLight").set(light & 0xFFFF, light >>> 16);
            buffer.bind();
            buffer.drawWithShader(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), cutoutShader);
        } finally {
            VertexBuffer.unbind();
            type.clearRenderState();
        }
    }

    private static void drawBuffered(byte[] vertices, RenderType type, PoseStack.Pose pose, MultiBufferSource buffers,
            int light, int overlay) {
        if (vertices.length == 0) {
            return;
        }
        ByteBuffer data = ByteBuffer.wrap(vertices).order(ByteOrder.nativeOrder());
        VertexConsumer consumer = buffers.getBuffer(type);
        Vector3f pos = new Vector3f();
        Vector3f normal = new Vector3f();
        for (int i = 0; i < vertices.length; i += STRIDE) {
            pose.pose().transformPosition(data.getFloat(i), data.getFloat(i + 4), data.getFloat(i + 8), pos);
            int rgba = i + COLOR_OFFSET;
            int color = FastColor.ARGB32.color(vertices[rgba + 3] & 255,
                    vertices[rgba] & 255, vertices[rgba + 1] & 255, vertices[rgba + 2] & 255);
            int uv = i + UV_OFFSET;
            int bakedLight = i + LIGHT_OFFSET;
            int blockLight = Math.max(light & 0xFFFF, data.getShort(bakedLight) & 0xFFFF);
            int skyLight = Math.max(light >>> 16, data.getShort(bakedLight + 2) & 0xFFFF);
            int n = i + NORMAL_OFFSET;
            pose.transformNormal(vertices[n] / 127.0F, vertices[n + 1] / 127.0F, vertices[n + 2] / 127.0F, normal);
            consumer.addVertex(pos.x(), pos.y(), pos.z(), color, data.getFloat(uv), data.getFloat(uv + 4),
                    overlay, skyLight << 16 | blockLight, normal.x(), normal.y(), normal.z());
        }
    }

    private record Mesh(byte[] cutout, byte[] translucent, @Nullable VertexBuffer cutoutBuffer, long bytes) {
        private void close() {
            if (cutoutBuffer != null) {
                cutoutBuffer.close();
            }
        }
    }
}

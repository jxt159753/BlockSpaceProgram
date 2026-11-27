package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.jxt159753.blockspaceprogram.assembly.PartPose;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign;
import io.github.jxt159753.blockspaceprogram.part.PartConnector;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

final class AssemblyViewport {
    private final int x;
    private final int y;
    private final int width;
    private final int height;
    private float yaw = -0.65F;
    private float pitch = 0.32F;
    private float zoomFactor = 1;

    AssemblyViewport(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    boolean contains(double mouseX, double mouseY) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    void orbit(double dx, double dy) {
        yaw = (yaw + (float) dx * 0.02F) % Mth.TWO_PI;
        pitch = Mth.clamp(pitch + (float) dy * 0.02F, -1.55F, 1.55F);
    }

    void zoom(double amount) {
        zoomFactor = Mth.clamp(zoomFactor * (float) Math.pow(1.2, amount), 0.45F, 6.0F);
    }

    Frame frame(ShipDesign design) {
        AABB bounds = design.bounds();
        Quaternionf view = new Quaternionf().rotationX(pitch).rotateY(yaw);
        var matrix = new Matrix3f().rotation(view);
        double projectedWidth = Math.abs(matrix.m00()) * bounds.getXsize() + Math.abs(matrix.m10()) * bounds.getYsize()
                + Math.abs(matrix.m20()) * bounds.getZsize();
        double projectedHeight = Math.abs(matrix.m01()) * bounds.getXsize() + Math.abs(matrix.m11()) * bounds.getYsize()
                + Math.abs(matrix.m21()) * bounds.getZsize();
        float fit = (float) Math.min((width - 16) / Math.max(1, projectedWidth), (height - 16) / Math.max(1, projectedHeight));
        return new Frame(bounds.getCenter(), view, Math.min(45, fit) * zoomFactor);
    }

    @Nullable
    Hit pick(Frame frame, ShipDesign design, double mouseX, double mouseY) {
        if (!contains(mouseX, mouseY)) {
            return null;
        }
        float rayX = (float) ((mouseX - (x + width / 2.0)) / frame.zoom());
        float rayY = (float) (((y + height / 2.0) - mouseY) / frame.zoom());
        var inverse = new Quaternionf(frame.view()).conjugate();
        Vec3 start = frame.center().add(new Vec3(inverse.transform(new Vector3f(rayX, rayY, -1024))));
        Vec3 end = frame.center().add(new Vec3(inverse.transform(new Vector3f(rayX, rayY, 1024))));
        Hit closest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (ShipDesign.Branch branch : design.tree(Set.of())) {
            var hit = AABB.clip(List.of(design.nodes().get(branch.node()).bounds()), start, end, BlockPos.ZERO);
            if (hit != null && start.distanceToSqr(hit.getLocation()) < distance) {
                distance = start.distanceToSqr(hit.getLocation());
                closest = new Hit(branch.node(), hit.getDirection(), hit.getLocation());
            }
        }
        return closest;
    }

    int stackMarker(ShipDesign design, Hit hit, Frame frame) {
        ShipDesign.Node node = design.nodes().get(hit.node());
        int index = -1;
        double nearest = 5 / frame.zoom();
        for (int i = 0; i < node.part().connectors().size(); i++) {
            PartConnector connector = node.part().connectors().get(i);
            if (!connector.kind().isStack() || node.pose().rotation().rotate(connector.normal()) != hit.normal()
                    || design.isMarkerUsed(hit.node(), i)) {
                continue;
            }
            AABB face = node.transform(connector.modelFace());
            double distance = Math.sqrt(face.distanceToSqr(hit.position()));
            if (distance < nearest) {
                nearest = distance;
                index = i;
            }
        }
        return index;
    }

    void render(GuiGraphics graphics, Frame frame, ShipDesign design, ShipDesign preview, @Nullable Hit hover, boolean showPorts) {
        graphics.fill(x, y, x + width, y + height, 0xFF131D2B);
        graphics.renderOutline(x, y, width, height, 0xFF3B4A60);
        graphics.enableScissor(x + 1, y + 1, x + width - 1, y + height - 1);
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(x + width / 2.0, y + height / 2.0, 120.0);
        pose.scale(frame.zoom(), -frame.zoom(), -frame.zoom());
        pose.mulPose(frame.view());
        pose.translate(-frame.center().x, -frame.center().y, -frame.center().z);

        Lighting.setupForEntityInInventory();
        RenderSystem.enableDepthTest();
        var buffers = graphics.bufferSource();
        var tree = design.tree(Set.of());
        for (ShipDesign.Branch branch : tree) {
            var node = design.nodes().get(branch.node());
            if (node.part() != null) {
                renderPart(node, pose, buffers, LightTexture.FULL_BRIGHT);
            }
        }
        buffers.endBatch();
        var lines = buffers.getBuffer(RenderType.lines());
        for (ShipDesign.Branch branch : tree) {
            ShipDesign.Node node = design.nodes().get(branch.node());
            if (node.hull() != null) {
                LevelRenderer.renderLineBox(pose, lines, node.bounds(), 0.45F, 0.65F, 0.82F, 1);
            } else if (showPorts) {
                for (int marker = 0; marker < node.part().connectors().size(); marker++) {
                    PartConnector connector = node.part().connectors().get(marker);
                    if (connector.kind().isStack() && !design.isMarkerUsed(branch.node(), marker)) {
                        renderConnector(pose, lines, node, connector);
                    }
                }
            }
        }
        if (hover != null) {
            LevelRenderer.renderLineBox(pose, lines, design.nodes().get(hover.node()).bounds().inflate(0.008),
                    1, 0.82F, 0.36F, 1);
        }
        for (int i = design.nodes().size(); i < preview.nodes().size(); i++) {
            ShipDesign.Node node = preview.nodes().get(i);
            LevelRenderer.renderLineBox(pose, lines, node.bounds().inflate(0.008), 0.42F, 1, 0.56F, 1);
            for (PartConnector connector : node.part().connectors()) {
                renderConnector(pose, lines, node, connector);
            }
        }
        buffers.endBatch();
        Lighting.setupFor3DItems();
        pose.popPose();
        graphics.disableScissor();
    }

    private static void renderConnector(PoseStack pose, VertexConsumer lines, ShipDesign.Node node, PartConnector connector) {
        Direction normal = node.pose().rotation().rotate(connector.normal());
        AABB face = node.transform(connector.modelFace()).move(Vec3.atLowerCornerOf(normal.getNormal()).scale(0.012));
        LevelRenderer.renderLineBox(pose, lines, face.inflate(0.002), 0.3F, 0.83F, 1.0F, 1);
    }

    static void renderPart(ShipDesign.Node node, PoseStack pose, MultiBufferSource buffers, int light) {
        pose.pushPose();
        Vec3 origin = node.pose().position();
        pose.translate(origin.x, origin.y, origin.z);
        pose.mulPose(PartPose.orientation(node.pose().rotation()));
        PartMeshRenderer.render(node.part(), pose, buffers, light);
        pose.popPose();
    }

    record Frame(Vec3 center, Quaternionf view, float zoom) {}
    record Hit(int node, Direction normal, Vec3 position) {}
}

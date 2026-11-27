package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Orthographic view of the inertial XY plane, with rotation to inspect inclined orbits. */
final class OrbitalMapViewport {
    private double yaw;
    private double tilt;
    private double zoom = 1;
    final int left;
    final int top;
    final int right;
    final int bottom;

    OrbitalMapViewport(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    Frame frame(Vec3 center, double radius) {
        double scale = Math.max(4, Math.min(right - left, bottom - top) / 2.0 - 15) * zoom / radius;
        return new Frame(center, (left + right) / 2f, (top + bottom) / 2f, scale,
                Math.cos(yaw), Math.sin(yaw), Math.cos(tilt), Math.sin(tilt));
    }

    boolean contains(double x, double y) {
        return x >= left && x < right && y >= top && y < bottom;
    }

    void rotate(double dx, double dy) {
        yaw = Math.IEEEremainder(yaw + dx * 0.015, 2 * Math.PI);
        tilt = Math.clamp(tilt + dy * 0.015, -1.5, 1.5);
    }

    void zoom(double amount) {
        zoom = Math.clamp(zoom * Math.pow(1.2, amount), 0.15, 32);
    }

    void background(GuiGraphics graphics) {
        graphics.fill(left, top, right, bottom, 0xff0c1524);
        for (int x = (left + right) / 2; x < right; x += 24) {
            graphics.vLine(x, top, bottom - 1, 0xff17263a);
        }
        for (int x = (left + right) / 2 - 24; x > left; x -= 24) {
            graphics.vLine(x, top, bottom - 1, 0xff17263a);
        }
        for (int y = (top + bottom) / 2; y < bottom; y += 24) {
            graphics.hLine(left, right - 1, y, 0xff17263a);
        }
        for (int y = (top + bottom) / 2 - 24; y > top; y -= 24) {
            graphics.hLine(left, right - 1, y, 0xff17263a);
        }
        graphics.renderOutline(left, top, right - left, bottom - top, 0xff32455d);
    }

    record Frame(Vec3 center, float x, float y, double scale, double cosYaw, double sinYaw, double cosTilt, double sinTilt) {
        Point project(Vec3 position) {
            Vec3 offset = position.subtract(center);
            double px = offset.x * cosYaw - offset.y * sinYaw;
            double py = (offset.x * sinYaw + offset.y * cosYaw) * cosTilt - offset.z * sinTilt;
            return new Point((float) (x + px * scale), (float) (y - py * scale));
        }
    }

    record Point(float x, float y) {
        double distanceSquared(double mouseX, double mouseY) {
            double dx = mouseX - x;
            double dy = mouseY - y;
            return dx * dx + dy * dy;
        }
    }

    static final class Ink {
        private final Matrix4f pose;
        private final VertexConsumer vertices;
        private final Frame frame;

        Ink(GuiGraphics graphics, Frame frame) {
            this.pose = graphics.pose().last().pose();
            this.vertices = graphics.bufferSource().getBuffer(RenderType.gui());
            this.frame = frame;
        }

        void path(List<Vec3> positions, Vec3 origin, int color, float width, boolean dashed) {
            for (int i = 1; i < positions.size(); i++) {
                if (!dashed || i % 4 < 3) {
                    line(frame.project(positions.get(i - 1).add(origin)), frame.project(positions.get(i).add(origin)), color, width);
                }
            }
        }

        void circle(Point center, float radius, int color, boolean filled) {
            int sides = 32;
            Point previous = new Point(center.x + radius, center.y);
            for (int side = 1; side <= sides; side++) {
                double angle = 2 * Math.PI * side / sides;
                Point next = new Point(center.x + radius * (float) Math.cos(angle), center.y + radius * (float) Math.sin(angle));
                if (filled) {
                    quad(center, previous, next, center, color);
                } else {
                    line(previous, next, color, 1);
                }
                previous = next;
            }
        }

        void diamond(Point center, float radius, int color) {
            quad(new Point(center.x, center.y - radius), new Point(center.x + radius, center.y),
                    new Point(center.x, center.y + radius), new Point(center.x - radius, center.y), color);
        }

        void line(Point a, Point b, int color, float width) {
            float dx = b.x - a.x;
            float dy = b.y - a.y;
            double length = Math.hypot(dx, dy);
            if (!(length > 0) || !Double.isFinite(length)) {
                return;
            }
            float ox = (float) (-dy * width / (2 * length));
            float oy = (float) (dx * width / (2 * length));
            quad(new Point(a.x - ox, a.y - oy), new Point(b.x - ox, b.y - oy),
                    new Point(b.x + ox, b.y + oy), new Point(a.x + ox, a.y + oy), color);
        }

        private void quad(Point a, Point b, Point c, Point d, int color) {
            // GUI projection flips the vertical axis, so wind vertices counter to screen coordinates.
            vertices.addVertex(pose, a.x, a.y, 0).setColor(color);
            vertices.addVertex(pose, d.x, d.y, 0).setColor(color);
            vertices.addVertex(pose, c.x, c.y, 0).setColor(color);
            vertices.addVertex(pose, b.x, b.y, 0).setColor(color);
        }
    }
}

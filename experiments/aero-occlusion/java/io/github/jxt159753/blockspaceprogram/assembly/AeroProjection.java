package io.github.jxt159753.blockspaceprogram.assembly;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.EnumMap;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Six orthographic silhouettes in ship coordinates. The windward side faces the ship's air velocity. */
final class AeroProjection {
    private static final double CELL_WIDTH = 0.25;
    private static final double CELL_AREA = CELL_WIDTH * CELL_WIDTH;
    private static final Side[] SIDES = Side.values();

    private final Surface[] surfaces = new Surface[SIDES.length];

    private AeroProjection(int nodeCount) {
        for (Side side : SIDES) {
            surfaces[side.ordinal()] = new Surface(nodeCount);
        }
    }

    double area(Side side) {
        return surfaces[side.ordinal()].area;
    }

    double exposedArea(Side side, int node) {
        return surfaces[side.ordinal()].nodeAreas[node];
    }

    double dragArea(Vector3dc airDirection) {
        double area = 0;
        for (int axis = 0; axis < 3; axis++) {
            double component = airDirection.get(axis);
            area += Math.abs(component) * surface(axis, component).dragArea;
        }
        return area;
    }

    Vector3d centerOfPressure(Vector3dc airDirection, Vector3d result) {
        result.zero();
        double area = 0;
        for (int axis = 0; axis < 3; axis++) {
            double component = airDirection.get(axis);
            double weight = Math.abs(component);
            Surface surface = surface(axis, component);
            result.fma(weight, surface.firstMoment);
            area += weight * surface.area;
        }
        return area > 0 ? result.div(area) : result;
    }

    void addMoment(Vector3dc airDirection, Vector3dc force, Vector3dc massCenter, double seconds, Vector3d moment) {
        Vector3d arm = centerOfPressure(airDirection, new Vector3d()).sub(massCenter);
        moment.fma(seconds, arm.cross(force));
    }

    private Surface surface(int axis, double component) {
        return surfaces[Side.of(axis, component).ordinal()];
    }

    enum Side {
        POS_X(0, 1), NEG_X(0, -1), POS_Y(1, 1), NEG_Y(1, -1), POS_Z(2, 1), NEG_Z(2, -1);

        final int axis;
        final int sign;
        final int uAxis;
        final int vAxis;

        Side(int axis, int sign) {
            this.axis = axis;
            this.sign = sign;
            uAxis = (axis + 1) % 3;
            vAxis = (axis + 2) % 3;
        }

        static Side of(int axis, double component) {
            return SIDES[2 * axis + (component < 0 ? 1 : 0)];
        }

        int depth(int x, int y, int z) {
            return coordinate(axis, x, y, z);
        }

        int u(int x, int y, int z) {
            return coordinate(uAxis, x, y, z);
        }

        int v(int x, int y, int z) {
            return coordinate(vAxis, x, y, z);
        }

        private static int coordinate(int axis, int x, int y, int z) {
            return switch (axis) {
                case 0 -> x;
                case 1 -> y;
                default -> z;
            };
        }
    }

    static final class Grid {
        private final EnumMap<Side, Long2ObjectOpenHashMap<Face>> faces = new EnumMap<>(Side.class);

        Grid() {
            for (Side side : SIDES) {
                faces.put(side, new Long2ObjectOpenHashMap<>());
            }
        }

        void addBlock(int x, int y, int z, int width, int node, double coefficient) {
            for (Side side : SIDES) {
                int depth = side.depth(x, y, z) + (side.sign > 0 ? width : 0);
                addFace(side, depth, side.u(x, y, z), side.v(x, y, z), width, node, coefficient);
            }
        }

        void addFace(Side side, int depth, int u, int v, int width, int node, double coefficient) {
            var plane = faces.get(side);
            var face = new Face(depth, node, coefficient);
            for (int i = u; i < u + width; i++) {
                for (int j = v; j < v + width; j++) {
                    long key = ((long) i << 32) | (j & 0xffffffffL);
                    var current = plane.get(key);
                    if (current == null || depth * side.sign > current.depth() * side.sign
                            || depth == current.depth() && node < current.node()) {
                        plane.put(key, face);
                    }
                }
            }
        }

        void forEachFace(Side side, FaceVisitor visitor) {
            var iterator = faces.get(side).long2ObjectEntrySet().fastIterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                long key = entry.getLongKey();
                visitor.accept((int) (key >> 32), (int) key, entry.getValue());
            }
        }

        int faceCount(Side side) {
            return faces.get(side).size();
        }

        AeroProjection summarize(int nodeCount) {
            var projection = new AeroProjection(nodeCount);
            for (Side side : SIDES) {
                var surface = projection.surfaces[side.ordinal()];
                surface.area = faceCount(side) * CELL_AREA;
                forEachFace(side, (u, v, face) -> {
                    surface.dragArea += face.coefficient() * CELL_AREA;
                    surface.nodeAreas[face.node()] += CELL_AREA;
                    surface.firstMoment.setComponent(side.axis, surface.firstMoment.get(side.axis)
                            + face.depth() * CELL_WIDTH * CELL_AREA);
                    surface.firstMoment.setComponent(side.uAxis, surface.firstMoment.get(side.uAxis)
                            + (u + 0.5) * CELL_WIDTH * CELL_AREA);
                    surface.firstMoment.setComponent(side.vAxis, surface.firstMoment.get(side.vAxis)
                            + (v + 0.5) * CELL_WIDTH * CELL_AREA);
                });
            }
            return projection;
        }
    }

    @FunctionalInterface
    interface FaceVisitor {
        void accept(int u, int v, Face face);
    }

    record Face(int depth, int node, double coefficient) {}

    private static final class Surface {
        final double[] nodeAreas;
        final Vector3d firstMoment = new Vector3d();
        double area;
        double dragArea;

        Surface(int nodeCount) {
            nodeAreas = new double[nodeCount];
        }
    }
}

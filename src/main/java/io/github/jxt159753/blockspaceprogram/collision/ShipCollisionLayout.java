package io.github.jxt159753.blockspaceprogram.collision;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class ShipCollisionLayout {
    private static final int QUARTERS = 4;

    private ShipCollisionLayout() {}

    /** Bounds in ship coordinates, in quarter-block units with an exclusive maximum. */
    public record PartBox(BlockPos min, BlockPos max, double mass) {
        public PartBox {
            if (min.getX() >= max.getX() || min.getY() >= max.getY() || min.getZ() >= max.getZ()
                    || !Double.isFinite(mass) || mass < 0) {
                throw new IllegalArgumentException("Invalid part box");
            }
            min = min.immutable();
            max = max.immutable();
        }

        /** The model's minimum corner is placed at originInQuarters, before rotation about that corner. */
        public static PartBox fromPart(BakedPart part, BlockPos originInQuarters, OctahedralGroup rotation) {
            if (rotation.transformation().determinant() < 0) {
                throw new IllegalArgumentException("The ship grid supports rotations only");
            }
            AABB bounds = part.localBounds();
            BlockPos opposite = originInQuarters
                    .relative(rotation.rotate(Direction.EAST), toQuarters(bounds.getXsize()))
                    .relative(rotation.rotate(Direction.UP), toQuarters(bounds.getYsize()))
                    .relative(rotation.rotate(Direction.SOUTH), toQuarters(bounds.getZsize()));
            return new PartBox(minimum(originInQuarters, opposite), maximum(originInQuarters, opposite),
                    part.properties().mass());
        }
    }

    /** Bounds within one block, also in quarters. Mass includes the contributions of all parts in the cell. */
    public record Cell(BlockPos min, BlockPos max, double mass) {
        private Cell enclose(Cell other) {
            return new Cell(minimum(min, other.min), maximum(max, other.max), mass + other.mass);
        }

        Vec3 inertiaPerUnitMass() {
            double x = (max.getX() - min.getX()) / (double) QUARTERS;
            double y = (max.getY() - min.getY()) / (double) QUARTERS;
            double z = (max.getZ() - min.getZ()) / (double) QUARTERS;
            return new Vec3((y * y + z * z) / 12, (x * x + z * z) / 12, (x * x + y * y) / 12);
        }
    }

    public static Map<BlockPos, Cell> rasterize(List<PartBox> parts) {
        Map<BlockPos, Cell> cells = new LinkedHashMap<>();
        for (PartBox part : parts) {
            BlockPos first = cellContaining(part.min);
            BlockPos last = cellContaining(part.max.offset(-1, -1, -1));
            double count = (last.getX() - first.getX() + 1.0) * (last.getY() - first.getY() + 1.0)
                    * (last.getZ() - first.getZ() + 1.0);
            for (BlockPos pos : BlockPos.betweenClosed(first, last)) {
                BlockPos lower = pos.multiply(QUARTERS);
                BlockPos min = maximum(part.min, lower).subtract(lower);
                BlockPos max = minimum(part.max, lower.offset(QUARTERS, QUARTERS, QUARTERS)).subtract(lower);
                cells.merge(pos.immutable(), new Cell(min, max, part.mass / count), Cell::enclose);
            }
        }
        return cells;
    }

    private static int toQuarters(double blocks) {
        double quarters = blocks * QUARTERS;
        if (quarters != Math.rint(quarters)) {
            throw new IllegalArgumentException("Part bounds must align with the quarter-block ship grid");
        }
        return (int) quarters;
    }

    private static BlockPos cellContaining(BlockPos pos) {
        return new BlockPos(Math.floorDiv(pos.getX(), QUARTERS), Math.floorDiv(pos.getY(), QUARTERS),
                Math.floorDiv(pos.getZ(), QUARTERS));
    }

    private static BlockPos minimum(BlockPos a, BlockPos b) {
        return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }

    private static BlockPos maximum(BlockPos a, BlockPos b) {
        return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }
}

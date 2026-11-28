package io.github.jxt159753.blockspaceprogram.collision;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntToDoubleFunction;
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
            requireRotation(rotation);
            AABB bounds = part.localBounds();
            BlockPos opposite = originInQuarters
                    .relative(rotation.rotate(Direction.EAST), toQuarters(bounds.getXsize()))
                    .relative(rotation.rotate(Direction.UP), toQuarters(bounds.getYsize()))
                    .relative(rotation.rotate(Direction.SOUTH), toQuarters(bounds.getZsize()));
            return new PartBox(minimum(originInQuarters, opposite), maximum(originInQuarters, opposite),
                    part.properties().mass());
        }

        public Iterable<BlockPos> coveredCells() {
            return ShipCollisionLayout.coveredCells(min, max);
        }
    }

    public static boolean isRotation(OctahedralGroup rotation) {
        var x = rotation.rotate(Direction.EAST).getNormal();
        var y = rotation.rotate(Direction.UP).getNormal();
        return x.cross(y).equals(rotation.rotate(Direction.SOUTH).getNormal());
    }

    private static void requireRotation(OctahedralGroup rotation) {
        if (!isRotation(rotation)) {
            throw new IllegalArgumentException("The ship grid supports rotations only");
        }
    }

    /** Bounds within one block, also in quarters. Mass includes the contributions of all parts in the cell. */
    public record Cell(BlockPos min, BlockPos max, double mass) {
        public Vec3 center(BlockPos pos) {
            return Vec3.atLowerCornerOf(pos)
                    .add((min.getX() + max.getX()) / (2.0 * QUARTERS),
                         (min.getY() + max.getY()) / (2.0 * QUARTERS),
                         (min.getZ() + max.getZ()) / (2.0 * QUARTERS));
        }

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

    /** Rasterizes the baked cubes with unit total mass so each cell stores its share of the part's volume. */
    public static Map<BlockPos, Cell> rasterize(BakedPart part, BlockPos originInQuarters, OctahedralGroup rotation) {
        requireRotation(rotation);
        int side = toQuarters(part.scale());
        Direction x = rotation.rotate(Direction.EAST);
        Direction y = rotation.rotate(Direction.UP);
        Direction z = rotation.rotate(Direction.SOUTH);
        BlockPos diagonal = BlockPos.ZERO.relative(x, side).relative(y, side).relative(z, side);
        double sharePerQuarterCube = 1.0 / (part.blocks().size() * side * side * side);
        Map<BlockPos, Cell> cells = new LinkedHashMap<>();
        for (var block : part.blocks()) {
            BlockPos pos = block.pos();
            BlockPos corner = originInQuarters.relative(x, pos.getX() * side)
                    .relative(y, pos.getY() * side).relative(z, pos.getZ() * side);
            BlockPos opposite = corner.offset(diagonal);
            addBox(cells, minimum(corner, opposite), maximum(corner, opposite), volume -> volume * sharePerQuarterCube);
        }
        return cells;
    }

    public static void addPart(Map<BlockPos, Cell> ship, Map<BlockPos, Cell> part, double mass) {
        if (!Double.isFinite(mass) || mass < 0) {
            throw new IllegalArgumentException("Invalid part mass");
        }
        part.forEach((pos, cell) ->
                ship.merge(pos, new Cell(cell.min, cell.max, cell.mass * mass), Cell::enclose));
    }

    public static Map<BlockPos, Cell> rasterize(List<PartBox> boxes) {
        Map<BlockPos, Cell> cells = new LinkedHashMap<>();
        for (PartBox box : boxes) {
            BlockPos first = cellContaining(box.min);
            BlockPos last = cellContaining(box.max.offset(-1, -1, -1));
            double count = (last.getX() - first.getX() + 1.0) * (last.getY() - first.getY() + 1.0)
                    * (last.getZ() - first.getZ() + 1.0);
            double massPerCell = box.mass / count;
            addBox(cells, box.min, box.max, ignored -> massPerCell);
        }
        return cells;
    }

    private static void addBox(Map<BlockPos, Cell> cells, BlockPos min, BlockPos max, IntToDoubleFunction massForVolume) {
        for (BlockPos pos : coveredCells(min, max)) {
            BlockPos lower = pos.multiply(QUARTERS);
            BlockPos cellMin = maximum(min, lower).subtract(lower);
            BlockPos cellMax = minimum(max, lower.offset(QUARTERS, QUARTERS, QUARTERS)).subtract(lower);
            var size = cellMax.subtract(cellMin);
            double mass = massForVolume.applyAsDouble(size.getX() * size.getY() * size.getZ());
            cells.merge(pos.immutable(), new Cell(cellMin, cellMax, mass), Cell::enclose);
        }
    }

    private static Iterable<BlockPos> coveredCells(BlockPos min, BlockPos max) {
        return BlockPos.betweenClosed(cellContaining(min), cellContaining(max.offset(-1, -1, -1)));
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

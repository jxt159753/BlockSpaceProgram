package io.github.jxt159753.blockspaceprogram.experiment.ship;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record ShipBody(ServerSubLevelContainer container, ServerSubLevel subLevel, BlockPos plotOrigin) {
    private static final int QUARTERS = 4;

    /** Bounds are already transformed into the ship frame, in quarter-block units with an exclusive maximum. */
    record PartBox(BlockPos min, BlockPos max, double mass) {
        PartBox {
            if (min.getX() >= max.getX() || min.getY() >= max.getY() || min.getZ() >= max.getZ()
                    || !Double.isFinite(mass) || mass < 0) {
                throw new IllegalArgumentException("Invalid part box");
            }
            min = min.immutable();
            max = max.immutable();
        }
    }

    record Cell(BlockPos min, BlockPos max, double mass) {
        Cell enclose(Cell other) {
            return new Cell(minimum(min, other.min), maximum(max, other.max), mass + other.mass);
        }

        Vec3 inertiaPerUnitMass() {
            double x = (max.getX() - min.getX()) / (double) QUARTERS;
            double y = (max.getY() - min.getY()) / (double) QUARTERS;
            double z = (max.getZ() - min.getZ()) / (double) QUARTERS;
            return new Vec3((y * y + z * z) / 12, (x * x + z * z) / 12, (x * x + y * y) / 12);
        }
    }

    static Map<BlockPos, Cell> rasterize(List<PartBox> parts) {
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

    static ShipBody spawn(ServerLevel level, String name, List<PartBox> parts,
                          Vector3dc worldOrigin, Quaterniondc orientation) {
        Map<BlockPos, Cell> cells = rasterize(parts);
        double mass = cells.values().stream().mapToDouble(Cell::mass).sum();
        if (!Double.isFinite(mass) || mass <= 0) {
            throw new IllegalArgumentException("A ship needs positive, finite mass");
        }

        ServerSubLevelContainer container = Objects.requireNonNull(SubLevelContainer.getContainer(level));
        Pose3d initial = new Pose3d();
        initial.position().set(worldOrigin);
        initial.orientation().set(orientation);
        ServerSubLevel subLevel = (ServerSubLevel) container.allocateNewSubLevel(initial);
        var plot = subLevel.getPlot();
        ShipBody ship = new ShipBody(container, subLevel, plot.getCenterBlock());
        try {
            subLevel.setName(name);
            cells.keySet().stream().map(pos -> new ChunkPos(ship.plotOrigin.offset(pos))).distinct()
                    .forEach(plot::newEmptyChunk);
            var blocks = plot.getEmbeddedLevelAccessor();
            cells.forEach((pos, cell) -> {
                if (!blocks.setBlock(pos, ShipExperiment.COLLISION_CELL.state(cell), Block.UPDATE_CLIENTS)) {
                    throw new IllegalStateException("Unable to place collision cell " + pos);
                }
            });
            ship.assignMass(cells);
            // Sable positions the body's center of mass, while the part boxes use the ship origin.
            Vec3 plotOffset = Vec3.atLowerCornerOf(ship.plotOrigin);
            Vector3d center = new Vector3d(subLevel.getMassTracker().getCenterOfMass());
            Vector3d position = orientation.transform(center.sub(plotOffset.x, plotOffset.y, plotOffset.z))
                    .add(worldOrigin);
            ship.pipeline().teleport(subLevel, position, orientation);
            subLevel.updateLastPose();
            subLevel.updateBoundingBox();
            container.addForceLoadTicket(subLevel, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
            return ship;
        } catch (RuntimeException error) {
            ship.remove();
            throw error;
        }
    }

    private void assignMass(Map<BlockPos, Cell> cells) {
        var tracker = subLevel.getSelfMassTracker();
        ServerLevel level = subLevel.getLevel();
        // Add the desired contributions before subtracting the block defaults to keep total mass positive.
        cells.forEach((pos, cell) -> {
            BlockPos absolute = plotOrigin.offset(pos);
            var state = level.getBlockState(absolute);
            if (cell.mass > 0) {
                tracker.addBlockMass(level, state, absolute, cell.mass, cell.inertiaPerUnitMass());
            }
        });
        cells.forEach((pos, cell) -> {
            BlockPos absolute = plotOrigin.offset(pos);
            var state = level.getBlockState(absolute);
            tracker.addBlockMass(level, state, absolute, -PhysicsBlockPropertyHelper.getMass(level, absolute, state),
                    PhysicsBlockPropertyHelper.getInertia(level, absolute, state));
        });
        subLevel.updateMergedMassData(0);
    }

    ShipBody rebuild(List<PartBox> parts, Motion previous) {
        ShipBody replacement = spawn(subLevel.getLevel(), subLevel.getName(), parts,
                pointInWorld(previous.pose, new Vector3d()), previous.pose.orientation());
        try {
            replacement.pipeline().addLinearAndAngularVelocity(replacement.subLevel,
                    previous.velocityAt(replacement.subLevel.logicalPose().position()), previous.angular);
        } catch (RuntimeException error) {
            replacement.remove();
            throw error;
        }
        remove();
        return replacement;
    }

    PhysicsPipeline pipeline() {
        return container.physicsSystem().getPipeline();
    }

    Motion motion() {
        if (subLevel.isRemoved()) {
            throw new IllegalStateException("Sable removed " + subLevel.getName());
        }
        Pose3d pose = pipeline().readPose(subLevel, new Pose3d(subLevel.logicalPose()));
        return new Motion(pose, pipeline().getLinearVelocity(subLevel, new Vector3d()),
                pipeline().getAngularVelocity(subLevel, new Vector3d()));
    }

    Vector3d pointInWorld(Pose3d pose, Vector3d point) {
        return pose.transformPosition(point.add(plotOrigin.getX(), plotOrigin.getY(), plotOrigin.getZ()));
    }

    void remove() {
        if (!subLevel.isRemoved()) {
            container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
        }
    }

    record Motion(Pose3d pose, Vector3d linear, Vector3d angular) {
        Vector3d velocityAt(Vector3dc worldPoint) {
            Vector3d offset = worldPoint.sub(pose.position(), new Vector3d());
            return angular.cross(offset, offset).add(linear);
        }
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

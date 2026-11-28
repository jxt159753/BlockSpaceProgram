package io.github.jxt159753.blockspaceprogram.collision;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout.Cell;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout.PartBox;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** A generated Sable body. Its owner manages the lifetime and regenerates it from the part layout. */
public record ShipCollisionBody(ServerSubLevelContainer container, ServerSubLevel subLevel, BlockPos plotOrigin) {
    private static final int HULL_UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    public record SolidBlock(BlockState state, double mass) {}

    /** Creates one body from the parts of a connected ship. Call on the server thread with Sable loaded. */
    public static ShipCollisionBody spawn(ServerLevel level, String name, List<PartBox> parts,
            Vector3dc worldOrigin, Quaterniondc orientation) {
        return spawn(level, name, ShipCollisionLayout.rasterize(parts), worldOrigin, orientation);
    }

    public static ShipCollisionBody spawn(ServerLevel level, String name, Map<BlockPos, Cell> cells,
            Vector3dc worldOrigin, Quaterniondc orientation) {
        return spawn(level, name, cells, Map.of(), worldOrigin, orientation);
    }

    public static ShipCollisionBody spawn(ServerLevel level, String name, Map<BlockPos, Cell> cells,
            Map<BlockPos, SolidBlock> hull, Vector3dc worldOrigin, Quaterniondc orientation) {
        double mass = cells.values().stream().mapToDouble(Cell::mass).sum()
                + hull.values().stream().mapToDouble(SolidBlock::mass).sum();
        if (!Double.isFinite(mass) || mass <= 0) {
            throw new IllegalArgumentException("A ship needs positive, finite mass");
        }
        var positions = positions(cells, hull);
        int lowest = positions.stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int highest = positions.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int minOriginY = level.getMinBuildHeight() - lowest;
        int maxOriginY = level.getMaxBuildHeight() - 1 - highest;
        if (minOriginY > maxOriginY) {
            throw new IllegalArgumentException("Collision cells exceed the ship plot height");
        }

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        Pose3d initial = new Pose3d();
        initial.position().set(worldOrigin);
        initial.orientation().set(orientation);
        ServerSubLevel subLevel = (ServerSubLevel) container.allocateNewSubLevel(initial);
        var plot = subLevel.getPlot();
        BlockPos plotCenter = plot.getCenterBlock();
        BlockPos origin = plotCenter.atY(Math.clamp(plotCenter.getY(), minOriginY, maxOriginY));
        ShipCollisionBody body = new ShipCollisionBody(container, subLevel, origin);
        try {
            subLevel.setName(name);
            body.placeBlocks(positions, cells, hull);
            body.assignMass(cells, hull);
            body.positionFromOrigin(worldOrigin, orientation);
            return body;
        } catch (RuntimeException error) {
            body.remove();
            throw error;
        }
    }

    private static Set<BlockPos> positions(Map<BlockPos, Cell> cells, Map<BlockPos, SolidBlock> hull) {
        var positions = new HashSet<>(cells.keySet());
        positions.addAll(hull.keySet());
        if (positions.size() != cells.size() + hull.size()) {
            throw new IllegalArgumentException("A collision cell overlaps a hull block");
        }
        return positions;
    }

    private void placeBlocks(Set<BlockPos> positions, Map<BlockPos, Cell> cells, Map<BlockPos, SolidBlock> hull) {
        var plot = subLevel.getPlot();
        Set<ChunkPos> missing = new HashSet<>();
        for (BlockPos pos : positions) {
            var absolute = plotOrigin.offset(pos);
            var chunk = new ChunkPos(absolute);
            if (!plot.contains(chunk) || subLevel.getLevel().isOutsideBuildHeight(absolute)) {
                throw new IllegalArgumentException("Ship blocks exceed the ship plot bounds");
            }
            if (plot.getChunkHolder(plot.toLocal(chunk)) == null) {
                missing.add(chunk);
            }
        }
        missing.forEach(plot::newEmptyChunk);
        var collisionBlock = BlockSpaceProgram.SHIP_COLLISION_CELL.get();
        cells.forEach((pos, cell) -> setPlotBlock(pos, collisionBlock.state(cell), Block.UPDATE_CLIENTS));
        hull.forEach((pos, block) -> setPlotBlock(pos, block.state(), HULL_UPDATE_FLAGS));
    }

    private void setPlotBlock(BlockPos pos, BlockState state, int flags) {
        var plot = subLevel.getPlot();
        var accessor = plot.getEmbeddedLevelAccessor();
        BlockPos local = pos.offset(plotOrigin.subtract(plot.getCenterBlock()));
        if (accessor.getBlockState(local) != state && !accessor.setBlock(local, state, flags)) {
            throw new IllegalStateException("Unable to write ship block " + pos);
        }
    }

    private void positionFromOrigin(Vector3dc worldOrigin, Quaterniondc orientation) {
        // Sable positions the center of mass, while the layout is relative to the ship origin.
        Vector3d center = new Vector3d(subLevel.getMassTracker().getCenterOfMass());
        Vector3d position = orientation.transform(center.sub(plotOrigin.getX(), plotOrigin.getY(), plotOrigin.getZ()))
                .add(worldOrigin);
        pipeline().teleport(subLevel, position, orientation);
        subLevel.updateLastPose();
        subLevel.updateBoundingBox();
    }

    /** Restores the immutable design into the existing plot so saved player tracking keeps its body id. */
    public void restoreBlocks(Map<BlockPos, Cell> cells, Map<BlockPos, SolidBlock> hull) {
        var pose = subLevel.logicalPose();
        var origin = pose.transformPosition(new Vector3d(plotOrigin.getX(), plotOrigin.getY(), plotOrigin.getZ()));
        var orientation = new Quaterniond(pose.orientation());
        var previousCenter = new Vector3d(pose.position());
        var pipeline = pipeline();
        var linear = pipeline.getLinearVelocity(subLevel, new Vector3d());
        var angular = pipeline.getAngularVelocity(subLevel, new Vector3d());
        var positions = positions(cells, hull);
        var extras = new ArrayList<BlockPos>();
        for (var holder : subLevel.getPlot().getLoadedChunks()) {
            var bounds = holder.getBoundingBox();
            if (bounds == null) {
                continue;
            }
            int x = holder.getPos().getMinBlockX();
            int z = holder.getPos().getMinBlockZ();
            for (BlockPos absolute : BlockPos.betweenClosed(x + bounds.minX(), bounds.minY(), z + bounds.minZ(),
                    x + bounds.maxX(), bounds.maxY(), z + bounds.maxZ())) {
                if (!holder.getChunk().getBlockState(absolute).isAir()) {
                    BlockPos local = absolute.subtract(plotOrigin);
                    if (!positions.contains(local)) {
                        extras.add(local);
                    }
                }
            }
        }
        placeBlocks(positions, cells, hull);
        extras.forEach(pos -> setPlotBlock(pos, Blocks.AIR.defaultBlockState(), HULL_UPDATE_FLAGS));
        subLevel.buildMassTracker();
        assignMass(cells, hull);
        positionFromOrigin(origin, orientation);
        // Keep the velocity at every ship-local point when the center of mass moves.
        var centerShift = subLevel.logicalPose().position().sub(previousCenter, new Vector3d());
        linear.add(angular.cross(centerShift, new Vector3d()));
        pipeline.resetVelocity(subLevel);
        pipeline.addLinearAndAngularVelocity(subLevel, linear, angular);
    }

    /** Restores the ship's mass after Sable builds its tracker from block defaults. */
    private void assignMass(Map<BlockPos, Cell> cells, Map<BlockPos, SolidBlock> hull) {
        ServerLevel level = subLevel.getLevel();
        // Add the part mass before removing the defaults so the tracker keeps a positive total.
        cells.forEach((pos, cell) -> {
            if (cell.mass() > 0) {
                changeCellMass(pos, cell, cell.mass());
            }
        });
        hull.forEach((pos, block) -> {
            BlockPos absolute = plotOrigin.offset(pos);
            double difference = block.mass() - PhysicsBlockPropertyHelper.getMass(level, absolute, block.state());
            if (difference != 0) {
                subLevel.getSelfMassTracker().addBlockMass(level, block.state(), absolute, difference,
                        PhysicsBlockPropertyHelper.getInertia(level, absolute, block.state()));
            }
        });
        cells.keySet().forEach(pos -> {
            BlockPos absolute = plotOrigin.offset(pos);
            var state = level.getBlockState(absolute);
            double mass = PhysicsBlockPropertyHelper.getMass(level, absolute, state);
            if (mass > 0) {
                subLevel.getSelfMassTracker().addBlockMass(
                        level, state, absolute, -mass, PhysicsBlockPropertyHelper.getInertia(level, absolute, state));
            }
        });
        subLevel.updateMergedMassData(0);
    }

    public void changeCellMass(BlockPos pos, Cell cell, double change) {
        BlockPos absolute = plotOrigin.offset(pos);
        ServerLevel level = subLevel.getLevel();
        subLevel.getSelfMassTracker().addBlockMass(
                level, level.getBlockState(absolute), absolute, change, cell.inertiaPerUnitMass());
    }

    public PhysicsPipeline pipeline() {
        return container.physicsSystem().getPipeline();
    }

    public void remove() {
        if (container.getSubLevel(subLevel.getUniqueId()) == subLevel) {
            container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
        }
    }
}

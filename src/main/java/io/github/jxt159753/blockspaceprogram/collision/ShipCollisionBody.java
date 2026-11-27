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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** A generated Sable body. Its owner manages the lifetime and regenerates it from the part layout. */
public record ShipCollisionBody(ServerSubLevelContainer container, ServerSubLevel subLevel, BlockPos plotOrigin) {
    /** Creates one body from the parts of a connected ship. Call on the server thread with Sable loaded. */
    public static ShipCollisionBody spawn(ServerLevel level, String name, List<PartBox> parts,
            Vector3dc worldOrigin, Quaterniondc orientation) {
        return spawn(level, name, ShipCollisionLayout.rasterize(parts), worldOrigin, orientation);
    }

    public static ShipCollisionBody spawn(ServerLevel level, String name, Map<BlockPos, Cell> cells,
            Vector3dc worldOrigin, Quaterniondc orientation) {
        double mass = cells.values().stream().mapToDouble(Cell::mass).sum();
        if (!Double.isFinite(mass) || mass <= 0) {
            throw new IllegalArgumentException("A ship needs positive, finite mass");
        }
        int lowest = cells.keySet().stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int highest = cells.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow();
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
            Set<ChunkPos> chunks = new HashSet<>();
            for (BlockPos pos : cells.keySet()) {
                BlockPos absolute = body.plotOrigin.offset(pos);
                ChunkPos chunk = new ChunkPos(absolute);
                if (!plot.contains(chunk)) {
                    throw new IllegalArgumentException("Collision cells exceed the ship plot bounds");
                }
                chunks.add(chunk);
            }

            subLevel.setName(name);
            chunks.forEach(plot::newEmptyChunk);
            var blocks = plot.getEmbeddedLevelAccessor();
            var collisionBlock = BlockSpaceProgram.SHIP_COLLISION_CELL.get();
            BlockPos plotOffset = origin.subtract(plotCenter);
            cells.forEach((pos, cell) -> {
                if (!blocks.setBlock(pos.offset(plotOffset), collisionBlock.state(cell), Block.UPDATE_CLIENTS)) {
                    throw new IllegalStateException("Unable to place collision cell " + pos);
                }
            });

            body.assignMass(cells);
            // Sable positions the center of mass, while the layout is relative to the ship origin.
            Vector3d center = new Vector3d(subLevel.getMassTracker().getCenterOfMass());
            Vector3d position = orientation.transform(center.sub(origin.getX(), origin.getY(), origin.getZ()))
                    .add(worldOrigin);
            body.pipeline().teleport(subLevel, position, orientation);
            subLevel.updateLastPose();
            subLevel.updateBoundingBox();
            return body;
        } catch (RuntimeException error) {
            body.remove();
            throw error;
        }
    }

    /** Restores the ship's mass after Sable builds its tracker from block defaults. */
    public void assignMass(Map<BlockPos, Cell> cells) {
        ServerLevel level = subLevel.getLevel();
        // Add the part mass before removing the defaults so the tracker keeps a positive total.
        cells.forEach((pos, cell) -> {
            if (cell.mass() > 0) {
                changeCellMass(pos, cell, cell.mass());
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

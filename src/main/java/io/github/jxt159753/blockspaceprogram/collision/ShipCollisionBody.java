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
        Map<BlockPos, Cell> cells = ShipCollisionLayout.rasterize(parts);
        double mass = cells.values().stream().mapToDouble(Cell::mass).sum();
        if (!Double.isFinite(mass) || mass <= 0) {
            throw new IllegalArgumentException("A ship needs positive, finite mass");
        }

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        Pose3d initial = new Pose3d();
        initial.position().set(worldOrigin);
        initial.orientation().set(orientation);
        ServerSubLevel subLevel = (ServerSubLevel) container.allocateNewSubLevel(initial);
        var plot = subLevel.getPlot();
        ShipCollisionBody body = new ShipCollisionBody(container, subLevel, plot.getCenterBlock());
        try {
            Set<ChunkPos> chunks = new HashSet<>();
            for (BlockPos pos : cells.keySet()) {
                BlockPos absolute = body.plotOrigin.offset(pos);
                ChunkPos chunk = new ChunkPos(absolute);
                if (level.isOutsideBuildHeight(absolute) || !plot.contains(chunk)) {
                    throw new IllegalArgumentException("Collision cells exceed the ship plot bounds");
                }
                chunks.add(chunk);
            }

            subLevel.setName(name);
            chunks.forEach(plot::newEmptyChunk);
            var blocks = plot.getEmbeddedLevelAccessor();
            var collisionBlock = BlockSpaceProgram.SHIP_COLLISION_CELL.get();
            cells.forEach((pos, cell) -> {
                if (!blocks.setBlock(pos, collisionBlock.state(cell), Block.UPDATE_CLIENTS)) {
                    throw new IllegalStateException("Unable to place collision cell " + pos);
                }
            });

            body.assignMass(cells);
            // Sable positions the center of mass, while the layout is relative to the ship origin.
            BlockPos origin = body.plotOrigin;
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

    private void assignMass(Map<BlockPos, Cell> cells) {
        var tracker = subLevel.getSelfMassTracker();
        ServerLevel level = subLevel.getLevel();
        // Add the part mass before removing the defaults so the tracker keeps a positive total.
        cells.forEach((pos, cell) -> {
            BlockPos absolute = plotOrigin.offset(pos);
            if (cell.mass() > 0) {
                tracker.addBlockMass(level, level.getBlockState(absolute), absolute, cell.mass(), cell.inertiaPerUnitMass());
            }
        });
        cells.keySet().forEach(pos -> {
            BlockPos absolute = plotOrigin.offset(pos);
            var state = level.getBlockState(absolute);
            double mass = PhysicsBlockPropertyHelper.getMass(level, absolute, state);
            if (mass > 0) {
                tracker.addBlockMass(level, state, absolute, -mass, PhysicsBlockPropertyHelper.getInertia(level, absolute, state));
            }
        });
        subLevel.updateMergedMassData(0);
    }

    public PhysicsPipeline pipeline() {
        return container.physicsSystem().getPipeline();
    }

    public void remove() {
        if (!subLevel.isRemoved()) {
            container.removeSubLevel(subLevel, SubLevelRemovalReason.REMOVED);
        }
    }
}

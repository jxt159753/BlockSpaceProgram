package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.assembly.AeroProjection.Grid;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Builds a flight-local snapshot from the currently loaded block data. */
final class ShipAerodynamics {
    private ShipAerodynamics() {}

    static AeroProjection project(ShipDesign design, IntPredicate ignoredNodes) {
        var grid = new Grid();
        for (int index = 0; index < design.nodes().size(); index++) {
            if (ignoredNodes.test(index)) {
                continue;
            }
            var node = design.nodes().get(index);
            if (node.hull() != null) {
                addHull(grid, node, index);
                continue;
            }
            int width = Math.round(node.part().scale() * 4);
            for (var block : node.part().blocks()) {
                if (block.state().isAir()) {
                    continue;
                }
                BlockPos a = shipQuarter(node, block.pos());
                BlockPos b = shipQuarter(node, block.pos().offset(1, 1, 1));
                grid.addBlock(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                        width, index, BlockPartProperties.get(block.state()).dragCoefficient());
            }
        }
        return grid.summarize(design.nodes().size());
    }

    static BlockPos shipQuarter(ShipDesign.Node node, BlockPos modelPoint) {
        return PartPose.snap(node.point(Vec3.atLowerCornerOf(modelPoint)));
    }

    static void addHull(Grid grid, ShipDesign.Node node, int index) {
        var origin = node.pose().origin();
        node.hull().forEach((pos, material) -> {
            BlockPos corner = origin.offset(pos.multiply(4));
            grid.addBlock(corner.getX(), corner.getY(), corner.getZ(), 4, index,
                    BlockPartProperties.get(material.state()).dragCoefficient());
        });
    }
}

package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.assembly.AeroProjection.Grid;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Builds a flight-local snapshot from the currently loaded block data. */
record ShipAerodynamics(AeroProjection projection, long buildNanos) {
    static ShipAerodynamics compute(ShipDesign design) {
        long start = System.nanoTime();
        var grid = new Grid();
        for (int index = 0; index < design.nodes().size(); index++) {
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
        var projection = grid.summarize(design.nodes().size());
        return new ShipAerodynamics(projection, System.nanoTime() - start);
    }

    static BlockPos shipQuarter(ShipDesign.Node node, BlockPos modelPoint) {
        return PartPose.snap(node.point(Vec3.atLowerCornerOf(modelPoint)));
    }

    static void addHull(Grid grid, ShipDesign.Node node, int index) {
        var origin = node.pose().origin();
        node.hull().forEach((pos, material) -> grid.addBlock(origin.getX() + 4 * pos.getX(),
                origin.getY() + 4 * pos.getY(), origin.getZ() + 4 * pos.getZ(), 4, index,
                BlockPartProperties.get(material.state()).dragCoefficient()));
    }
}

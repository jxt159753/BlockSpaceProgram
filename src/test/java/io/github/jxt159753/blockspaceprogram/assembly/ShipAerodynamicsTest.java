package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.assembly.AeroProjection.Side;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign.Node;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.loading.LoadingModList;
import org.joml.Vector3d;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ShipAerodynamicsTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    public void rotatedHalfSizePartAndHullShareTheQuarterGrid() {
        var stone = Blocks.STONE.defaultBlockState();
        var part = BakedPart.bake(Map.of(BlockPos.ZERO, stone, new BlockPos(2, 0, 0), stone))
                .orElseThrow().withScale(0.5F);
        var rotation = PartPose.align(Direction.EAST, Direction.UP, Direction.NORTH, Direction.UP);
        var pose = new PartPose(new BlockPos(1, 4, 4), rotation);
        var hull = HullRegion.capture(Map.of(BlockPos.ZERO, stone), BlockPos.ZERO);
        var design = new ShipDesign(List.of(Node.hull(hull, BlockPos.ZERO), new Node(part, pose, -1)), List.of(), 1);

        var projection = ShipAerodynamics.project(design, new BitSet());
        assertEquals(1.25, projection.area(Side.POS_Y), 0);
        assertEquals(0.75, projection.exposedArea(Side.POS_Y, 0), 0);
        assertEquals(0.50, projection.exposedArea(Side.POS_Y, 1), 0);
        assertEquals(1.00, projection.exposedArea(Side.NEG_Y, 0), 0);
        assertEquals(0.25, projection.exposedArea(Side.NEG_Y, 1), 0);
        assertEquals(1.25, projection.dragArea(new Vector3d(0, 1, 0)), 0);
    }
}

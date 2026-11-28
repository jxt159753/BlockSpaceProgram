package io.github.jxt159753.blockspaceprogram.collision;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionLayout.Cell;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ShipCollisionLayoutTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    public void leavesHollowCabinAndDoorEmptyWithoutLosingMass() {
        Map<BlockPos, BlockState> structure = new HashMap<>();
        var door = new BlockPos(3, 1, 0);
        for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 6, 6, 6)) {
            if (pos.getX() == 0 || pos.getX() == 6 || pos.getY() == 0 || pos.getY() == 6
                    || pos.getZ() == 0 || pos.getZ() == 6) {
                structure.put(pos.immutable(), Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        structure.remove(door);
        structure.remove(door.above());
        var part = BakedPart.bake(structure).orElseThrow();
        var layout = ShipCollisionLayout.rasterize(part, BlockPos.ZERO, OctahedralGroup.IDENTITY);
        assertEquals(216, layout.size());
        assertEquals(structure.keySet(), layout.keySet());
        assertFalse(layout.containsKey(new BlockPos(3, 3, 3)));
        assertFalse(layout.containsKey(door));
        var cells = new HashMap<BlockPos, Cell>();
        double mass = part.properties().mass();
        ShipCollisionLayout.addPart(cells, layout, mass);
        assertEquals(mass, cells.values().stream().mapToDouble(Cell::mass).sum(), 1e-9);
    }

    @Test
    public void clipsRotatedCubesAndCountsVolumeBeforeEnclosingDisjointPieces() {
        var halfTurn = Arrays.stream(OctahedralGroup.values()).filter(rotation -> ShipCollisionLayout.isRotation(rotation)
                && rotation.rotate(Direction.EAST) == Direction.WEST && rotation.rotate(Direction.UP) == Direction.UP)
                .findFirst().orElseThrow();
        var slab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
        var part = BakedPart.bake(Map.of(BlockPos.ZERO, slab, new BlockPos(2, 0, 0), slab))
                .orElseThrow().withScale(0.5F);
        var layout = ShipCollisionLayout.rasterize(part, new BlockPos(1, 0, 2), halfTurn);
        assertEquals(Map.of(
                new BlockPos(-2, 0, 0), new Cell(new BlockPos(3, 0, 0), new BlockPos(4, 2, 2), 0.25),
                new BlockPos(-1, 0, 0), new Cell(BlockPos.ZERO, new BlockPos(4, 2, 2), 0.5),
                BlockPos.ZERO, new Cell(BlockPos.ZERO, new BlockPos(1, 2, 2), 0.25)), layout);
    }
}

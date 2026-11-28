package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import io.github.jxt159753.blockspaceprogram.part.PartEngine;
import io.github.jxt159753.blockspaceprogram.part.PartRcsThruster;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.joml.Vector3d;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ShipRcsTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    public void assemblyShowsMaximumPureThrustForEachAxis() {
        var center = new Vec3(0.5, 0.5, 0.5);
        var design = design(Arrays.stream(Direction.values()).map(face -> thruster(center.relative(face, 0.5), face)).toList());
        var forces = ShipRcs.pureTranslation(design, 0);
        var jets = ShipRcs.mounts(design, new int[]{0}).stream().map(mount -> mount.jet(new Vector3d(0.5))).toList();
        for (Direction direction : Direction.values()) {
            var axis = new Vector3d(direction.step());
            var result = RcsAllocator.allocate(jets, axis.mul(350, new Vector3d()), new Vector3d(), 0);
            assertEquals(350, forces[direction.ordinal()], 0.05);
            assertEquals(result.force().dot(axis), forces[direction.ordinal()], 1e-6);
        }
    }

    @Test
    public void assemblyMarksOffsetNozzleWithoutTorqueCompensationUnavailable() {
        var design = design(List.of(thruster(new Vec3(0, 0.875, 0.5), Direction.WEST)));
        for (double force : ShipRcs.pureTranslation(design, 0)) {
            assertEquals(0, force, 0);
        }
        assertEquals(350, ShipRcs.pureTranslation(design, 1_500)[Direction.EAST.ordinal()], 0.05);
    }

    private static ShipDesign design(List<PartRcsThruster> rcs) {
        var sample = BakedPart.bake(Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState())).orElseThrow();
        var properties = new BlockPartProperties(60, Map.of(ResourceLocation.parse("blockspaceprogram:fuel"), 40.0),
                0, 0, 0, 1, 0, 0, 0, 8);
        var part = new BakedPart(sample.geometry(), properties, sample.centerOfMass(), sample.inertia(),
                Set.of(PartType.COMMAND_POD), 1, List.of(), List.of(), Optional.empty(), Optional.empty(), List.of(), rcs);
        return new ShipDesign(List.of(new ShipDesign.Node(part, PartPose.ROOT, -1)), List.of(), 1);
    }

    private static PartRcsThruster thruster(Vec3 point, Direction face) {
        return new PartRcsThruster(new PartEngine.Outlet(point, face), new PartRcsThruster.Performance(350, 220, 0));
    }
}

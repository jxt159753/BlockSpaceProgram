package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import io.github.jxt159753.blockspaceprogram.part.EnginePerformance;
import io.github.jxt159753.blockspaceprogram.part.PartEngine;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.joml.Vector3d;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PartEnginesTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    public void separateEnginesShareFuelButOnlyDrawElectricityWhenRequired() {
        var sample = BakedPart.bake(Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState())).orElseThrow();
        var chemical = new PartEngine(new PartEngine.Outlet(new Vec3(0, 0.5, 0.5), Direction.WEST),
                new EnginePerformance(20_000, 250, 235), 0);
        var electric = new PartEngine(new PartEngine.Outlet(new Vec3(1, 0.5, 0.5), Direction.EAST),
                new EnginePerformance(4_000, 2_000, 1_500), 50_000_000);
        var rating = EnginePerformance.combine(List.of(chemical.performance(), electric.performance()));
        var properties = new BlockPartProperties(1_000,
                Map.of(ResourceLocation.fromNamespaceAndPath("blockspaceprogram", "fuel"), 100.0),
                rating.thrust(), rating.vacuumSpecificImpulse(), rating.seaLevelSpecificImpulse(),
                0, 0, 250_000_000, 50_000_000, 8);
        var part = new BakedPart(sample.geometry(), properties, sample.centerOfMass(), sample.inertia(),
                Set.of(PartType.COMMAND_POD, PartType.ENGINE, PartType.FUEL_TANK), 1, List.of(), List.of(chemical, electric));
        var design = new ShipDesign(List.of(ShipDesign.Node.placed(part, PartPose.ROOT)), List.of(), 1);

        var stage = StagePerformance.calculate(design).getFirst();
        assertEquals(new Vector3d(16_000, 0, 0), stage.thrust());
        assertEquals(12.1333125, stage.burnTime(), 1e-9);
        assertEquals(16_000 / (1_100 * VacuumPropulsion.STANDARD_GRAVITY), stage.thrustToWeight(), 1e-9);

        var encoded = BakedPart.CODEC.encodeStart(NbtOps.INSTANCE, part).getOrThrow();
        var decoded = BakedPart.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(part.engines(), decoded.withScale(0.25F).engines());
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            BakedPart.STREAM_CODEC.encode(buffer, decoded);
            assertEquals(part.engines(), BakedPart.STREAM_CODEC.decode(buffer).engines());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
    }
}

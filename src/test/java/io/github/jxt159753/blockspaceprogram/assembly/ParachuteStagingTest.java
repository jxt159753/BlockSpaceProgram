package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.ParachutePerformance;
import io.github.jxt159753.blockspaceprogram.part.PartParachute;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class ParachuteStagingTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    public void parachuteStageAndBakedPerformanceRoundTripIndependentlyOfEngineStage() {
        var chute = new PartParachute(new Vec3(0.5, 0.5, 0.5), new ParachutePerformance(20, 400, 0.02, 250, 500, 2));
        var sample = BakedPart.bake(Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState())).orElseThrow();
        var model = Optional.of(ResourceLocation.parse("blockspaceprogram:part/test"));
        var name = Optional.<Component>of(Component.literal("Test part"));
        var part = new BakedPart(sample.geometry(), sample.properties(), sample.centerOfMass(), sample.inertia(),
                Set.of(PartType.COMMAND_POD, PartType.ENGINE, PartType.PARACHUTE), 0.5F, List.of(), List.of(),
                model, name, List.of(chute));
        var design = new ShipDesign(List.of(ShipDesign.Node.placed(part, PartPose.ROOT)), List.of(), 3)
                .moveParachute(0, 2).moveEngine(0, 1);
        assertEquals(1, design.nodes().getFirst().engineStage());
        assertEquals(2, design.nodes().getFirst().parachuteStage());
        assertFalse(design.lastStageEmpty());
        var saved = ShipDesign.CODEC.encodeStart(NbtOps.INSTANCE, design).getOrThrow();
        assertEquals(design, ShipDesign.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow());

        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            ShipDesign.FLIGHT_STREAM_CODEC.encode(buffer, design);
            var decoded = ShipDesign.FLIGHT_STREAM_CODEC.decode(buffer).nodes().getFirst();
            assertEquals(1, decoded.engineStage());
            assertEquals(2, decoded.parachuteStage());
            assertEquals(List.of(chute), decoded.part().parachutes());
            assertEquals(model, decoded.part().displayModel());
            assertEquals(name, decoded.part().name());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
        var subset = design.subset(List.of(0), Set.of(), new PartPose(BlockPos.ZERO, OctahedralGroup.IDENTITY), 4, stage -> stage + 1);
        assertEquals(2, subset.nodes().getFirst().engineStage());
        assertEquals(3, subset.nodes().getFirst().parachuteStage());
    }
}

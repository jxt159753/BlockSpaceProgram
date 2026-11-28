package io.github.jxt159753.blockspaceprogram.part;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PartTemplateTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    public void snapshotsMultipleEnginesAndDisplayFieldsOnDiskAndWire() {
        var part = decode("""
                {
                  "name": {"translate": "test.engine", "fallback": "Twin Engine"},
                  "size": [3, 2, 3],
                  "connectors": [{"kind": "stack", "center": [1.5, 2, 1.5], "normal": "up", "width": 3, "height": 3}],
                  "engines": [
                    {"outlet": {"position": [0.5, 0, 1.5], "face": "down"},
                     "performance": {"thrust": 6000, "vacuum_specific_impulse": 350, "sea_level_specific_impulse": 235}},
                    {"outlet": {"position": [2.5, 0, 1.5], "face": "down"},
                     "performance": {"thrust": 6000, "vacuum_specific_impulse": 350, "sea_level_specific_impulse": 235},
                     "electric_power": 500}
                  ],
                  "properties": {"mass": 220, "electric_capacity": 4000},
                  "display_model": "test:part/twin_engine"
                }
                """).bake().getOrThrow().withScale(0.25F);
        assertEquals(18, part.blocks().size());
        assertEquals(new Vec3(0.75, 0.5, 0.75), part.modelToLocal(part.geometry().size()));
        assertEquals(3, part.connectors().getFirst().sizeQuarters());
        assertEquals(2, part.engines().size());
        assertEquals(12_000, part.properties().thrust(), 0);
        assertEquals(500, part.properties().electricPower(), 0);
        assertEquals(4_000, part.properties().electricCapacity(), 0);
        assertEquals(ResourceLocation.parse("test:part/twin_engine"), part.displayModel().orElseThrow());

        var nbt = BakedPart.CODEC.encodeStart(NbtOps.INSTANCE, part).getOrThrow();
        assertEquals(part, BakedPart.CODEC.parse(NbtOps.INSTANCE, nbt).getOrThrow());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            BakedPart.STREAM_CODEC.encode(buffer, part);
            buffer.writeInt(123);
            var synced = BakedPart.STREAM_CODEC.decode(buffer);
            assertEquals(part.geometry().size(), synced.geometry().size());
            assertEquals(part.connectors(), synced.connectors());
            assertEquals(part.engines(), synced.engines());
            assertEquals(part.displayModel(), synced.displayModel());
            assertEquals(part.name(), synced.name());
            assertEquals(123, buffer.readInt());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void nameIdentifiesTemplateWithoutDisplayModelAndSizesAreBounded() {
        String template = """
                {"name": "Panel", "size": [%d, 1, 1], "connectors": [], "engines": [], "properties": {}}
                """;
        var part = decode(template.formatted(BakedPart.MAX_SIDE)).bake().getOrThrow();
        assertTrue(part.isTemplatePart());
        assertTrue(part.displayModel().isEmpty());
        assertTrue(part.rcs().isEmpty());
        assertEquals(0, part.properties().wheelTorque(), 0);
        assertEquals(BakedPart.MAX_SIDE, part.blocks().size());
        assertFalse(decode(template.formatted(BakedPart.MAX_SIDE + 1)).bake().isSuccess());
    }

    @Test
    public void copiesTheSameRcsOutletsFromBlocksAndTemplates() {
        var block = Blocks.DISPENSER.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.EAST);
        var west = block.setValue(BlockStateProperties.FACING, Direction.WEST);
        var structure = Map.of(new BlockPos(8, 16, 24), block, new BlockPos(9, 16, 24), west);
        var profile = new PartRcsThruster.Performance(350, 220, 0);
        var baked = BakedPart.bake(structure, state -> profile).orElseThrow();
        assertTrue(baked.engines().isEmpty());
        var template = decode("""
                {
                  "name": "RCS pair", "size": [2, 1, 1], "connectors": [], "engines": [],
                  "properties": {"wheel_torque": 23},
                  "rcs": [
                    {"outlet": {"position": [1, 0.5, 0.5], "face": "east"},
                     "performance": {"thrust": 350, "isp": 220}},
                    {"outlet": {"position": [1, 0.5, 0.5], "face": "west"},
                     "performance": {"thrust": 350, "isp": 220}}
                  ]
                }
                """).bake().getOrThrow().withScale(0.25F);
        assertEquals(baked.rcs(), template.rcs());
        assertEquals(23, template.properties().wheelTorque(), 0);
        var nbt = BakedPart.CODEC.encodeStart(NbtOps.INSTANCE, baked).getOrThrow();
        assertEquals(baked.rcs(), BakedPart.CODEC.parse(NbtOps.INSTANCE, nbt).getOrThrow().rcs());
        assertFalse(baked.rcs().equals(BakedPart.bake(structure,
                state -> new PartRcsThruster.Performance(500, 220, 30)).orElseThrow().rcs()));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            BakedPart.STREAM_CODEC.encode(buffer, template);
            buffer.writeInt(123);
            var synced = BakedPart.STREAM_CODEC.decode(buffer);
            assertEquals(template.rcs(), synced.rcs());
            assertEquals(23, synced.properties().wheelTorque(), 0);
            assertEquals(123, buffer.readInt());
        } finally {
            buffer.release();
        }
    }

    private static PartTemplate decode(String json) {
        return PartTemplate.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }
}

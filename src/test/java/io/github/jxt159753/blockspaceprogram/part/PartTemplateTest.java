package io.github.jxt159753.blockspaceprogram.part;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
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
        assertEquals(BakedPart.MAX_SIDE, part.blocks().size());
        assertFalse(decode(template.formatted(BakedPart.MAX_SIDE + 1)).bake().isSuccess());
    }

    private static PartTemplate decode(String json) {
        return PartTemplate.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }
}

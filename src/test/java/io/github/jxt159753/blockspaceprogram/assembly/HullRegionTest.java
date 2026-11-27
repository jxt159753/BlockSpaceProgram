package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HullRegionTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    public void encodesPaletteAndPositionsBeyondTheBakingRange() {
        var origin = new BlockPos(10, 50, 4);
        var blocks = new LinkedHashMap<BlockPos, BlockState>();
        blocks.put(origin, Blocks.SMOOTH_STONE.defaultBlockState());
        blocks.put(origin.offset(-20, 15, 35), Blocks.SMOOTH_STONE.defaultBlockState());
        blocks.put(origin.offset(11, -12, -7), Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.OPEN, true));
        var region = HullRegion.capture(blocks, origin);
        var encoded = (CompoundTag) HullRegion.CODEC.encodeStart(NbtOps.INSTANCE, region).getOrThrow();
        assertEquals(2, encoded.getList("palette", Tag.TAG_COMPOUND).size());
        assertEquals(3, encoded.getLongArray("positions").length);
        assertEquals(3, encoded.getIntArray("indices").length);

        var decoded = HullRegion.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        var states = new LinkedHashMap<BlockPos, BlockState>();
        decoded.forEach((pos, material) -> states.put(origin.offset(pos), material.state()));
        assertEquals(blocks, states);
        assertEquals(region, decoded);
        assertEquals(region.bounds(), decoded.bounds());
        assertEquals(region.mass(), decoded.mass(), 0);

        encoded.putIntArray("indices", new int[]{0, 8, 1});
        assertTrue(HullRegion.CODEC.parse(NbtOps.INSTANCE, encoded).error().isPresent());
    }

    @Test
    public void readsLegacySavedBlueprintAndFlightLayoutWithoutHullFields() {
        var pod = commandPod();
        var legacyNode = new CompoundTag();
        legacyNode.put("part", BakedPart.CODEC.encodeStart(NbtOps.INSTANCE, pod).getOrThrow());
        legacyNode.put("pose", PartPose.CODEC.encodeStart(NbtOps.INSTANCE, PartPose.ROOT).getOrThrow());
        var legacy = new CompoundTag();
        var nodes = new ListTag();
        nodes.add(legacyNode);
        legacy.put("nodes", nodes);
        legacy.put("links", new ListTag());
        legacy.putInt("stages", 1);

        var expected = new ShipDesign(List.of(new ShipDesign.Node(pod, PartPose.ROOT, -1)), List.of(), 1);
        assertEquals(expected, ShipDesign.CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow());
        assertEquals(expected, ShipDesign.FLIGHT_CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow());
        assertEquals(legacy, ShipDesign.FLIGHT_CODEC.encodeStart(NbtOps.INSTANCE, expected).getOrThrow());
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ShipDesign.STREAM_CODEC.encode(buffer, expected);
            var legacyNetworkDesign = ShipDesign.STREAM_CODEC.decode(new FriendlyByteBuf(buffer.duplicate()));
            assertEquals(legacyNetworkDesign, ShipDesign.FLIGHT_STREAM_CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    @Test
    public void preservesHullInFlightAndCountsItsMassWithoutConsumingAPartSlot() {
        var pod = new ShipDesign.Node(commandPod(), PartPose.ROOT, -1);
        var hull = HullRegion.capture(Map.of(BlockPos.ZERO, Blocks.SMOOTH_STONE.defaultBlockState()), BlockPos.ZERO);
        var node = ShipDesign.Node.hull(hull, new BlockPos(0, 0, 1));
        var connection = new ShipDesign.Link(new ShipDesign.Endpoint(1, -1, Direction.NORTH, new Vec3(0.5, 0.5, 0)),
                new ShipDesign.Endpoint(0, -1, Direction.SOUTH, new Vec3(0.5, 0.5, 1)), true, false, -1);
        var design = new ShipDesign(List.of(pod, node), List.of(connection), 1);
        assertEquals(1, design.partCount());
        assertEquals(pod.dryMass() + hull.mass(), design.fullMass(), 0);
        assertTrue(design.isValidLayout());
        assertFalse(design.isValid());

        Tag saved = ShipDesign.FLIGHT_CODEC.encodeStart(NbtOps.INSTANCE, design).getOrThrow();
        assertEquals(design, ShipDesign.FLIGHT_CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow());
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ShipDesign.FLIGHT_STREAM_CODEC.encode(buffer, design);
            assertEquals(design, ShipDesign.FLIGHT_STREAM_CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
    }

    private static BakedPart commandPod() {
        var sample = BakedPart.bake(Map.of(BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState())).orElseThrow();
        return new BakedPart(sample.geometry(), sample.properties(), sample.centerOfMass(), sample.inertia(),
                Set.of(PartType.COMMAND_POD), 1);
    }
}

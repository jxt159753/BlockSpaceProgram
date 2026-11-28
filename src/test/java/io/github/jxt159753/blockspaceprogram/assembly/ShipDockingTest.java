package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign.Endpoint;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDocking.CaptureLimits;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDocking.Port;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDocking.PortKey;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDockingState.Identity;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDockingState.StageBlock;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.PartConnector;
import io.github.jxt159753.blockspaceprogram.part.PartType;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.LoadingModList;
import org.joml.Quaterniond;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.*;

public class ShipDockingTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    @Test
    public void capturesOnlyMatchingPortsWithinDistanceSpeedAndAngleLimits() {
        Port a = port(new Vec3(2, 1, 1), Direction.EAST, 8);
        Port b = port(new Vec3(0, 1, 1), Direction.WEST, 8);
        var limits = CaptureLimits.of(0.25, 0.5, 8);
        var alignment = ShipDocking.capture(a, b, new Quaterniond().rotateX(Math.toRadians(92)),
                new Vec3(0.1, 0, 0), new Vec3(-0.3, 0, 0), limits);
        assertNotNull(alignment);
        assertEquals(0, alignment.point(b.center()).distanceToSqr(a.center()), 0);
        assertEquals(Direction.SOUTH, alignment.rotation().rotate(Direction.UP));
        assertEquals(new BlockPos(8, 8, 0), alignment.origin());

        assertNull(ShipDocking.capture(a, port(b.center(), Direction.WEST, 16), new Quaterniond(),
                Vec3.ZERO, Vec3.ZERO, limits));
        assertNull(ShipDocking.capture(a, b, new Quaterniond(), new Vec3(0.26, 0, 0), Vec3.ZERO, limits));
        assertNull(ShipDocking.capture(a, b, new Quaterniond(), Vec3.ZERO, new Vec3(0.51, 0, 0), limits));
        assertNull(ShipDocking.capture(a, b, new Quaterniond().rotateX(Math.toRadians(12)),
                Vec3.ZERO, Vec3.ZERO, limits));
        assertNull(ShipDocking.capture(a, b, new Quaterniond().rotateZ(Math.toRadians(12)),
                Vec3.ZERO, Vec3.ZERO, limits));
    }

    @Test
    public void dockingFrameRoundTripsQuarterGridAndPartOrientation() {
        PartPose frame = ShipDocking.capture(port(new Vec3(2, 1, 1), Direction.EAST, 8),
                port(new Vec3(0, 1, 1), Direction.WEST, 8), new Quaterniond().rotateX(Math.PI / 2),
                Vec3.ZERO, Vec3.ZERO, CaptureLimits.of(0.25, 0.5, 8));
        var part = new PartPose(new BlockPos(-11, 17, 23), OctahedralGroup.IDENTITY);
        assertNotNull(frame);
        assertEquals(part, part.in(frame).relativeTo(frame));
        assertEquals(PartPose.ROOT, frame.relativeTo(frame));
    }

    @Test
    public void mergesHullBlocksAndAttachmentsOnTheBlockGrid() {
        var primary = hullShip(Direction.EAST);
        var first = port(primary);
        for (var yaw : Rotation.values()) {
            var axes = PartPose.align(Direction.UP, Direction.NORTH, Direction.UP, yaw.rotate(Direction.NORTH));
            var yawFrame = new PartPose(BlockPos.ZERO, axes);
            var incoming = hullShip(yawFrame.localDirection(Direction.WEST));
            var second = port(incoming);
            var frame = ShipDocking.capture(first, second, new Quaterniond(PartPose.orientation(axes)).normalize(),
                    Vec3.ZERO, Vec3.ZERO, CaptureLimits.of(0.25, 0.5, 8));
            assertNotNull(frame);
            var merged = ShipDocking.merge(primary, incoming, frame, first, second);
            assertNotNull(merged);
            assertTrue(merged.isValidLayout());
            assertEquals(2, merged.partCount());
            assertEquals(4, merged.nodes().size());
            var source = incoming.nodes().getLast();
            var target = merged.nodes().getLast();
            assertEquals(source.hull().mass(), target.hull().mass(), 0);
            assertEquals(1, target.hull().size());
            var cell = BlockPos.containing(frame.point(Vec3.atCenterOf(source.hullOrigin())));
            target.hull().forEach((pos, material) -> {
                assertEquals(cell, target.hullOrigin().offset(pos));
                assertEquals(yaw.rotate(Direction.NORTH), material.state().getValue(BlockStateProperties.HORIZONTAL_FACING));
            });
            assertEquals(List.of(List.of(0, 1), List.of(2, 3)), merged.components(Set.of(merged.links().size() - 1)));
            var saved = ShipDesign.FLIGHT_CODEC.encodeStart(NbtOps.INSTANCE, merged).getOrThrow();
            assertEquals(merged, ShipDesign.FLIGHT_CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow());
        }
    }

    @Test
    public void rejectsIncomingHullOutsideTheUprightBlockGrid() {
        var primary = hullShip(Direction.EAST);
        var incoming = hullShip(Direction.WEST);
        var first = port(primary);
        var second = port(incoming);
        var limits = CaptureLimits.of(0.25, 0.5, 8);
        var roll = ShipDocking.capture(first, second, new Quaterniond().rotateX(Math.PI / 2),
                Vec3.ZERO, Vec3.ZERO, limits);
        assertNotNull(roll);
        assertNull(ShipDocking.merge(primary, incoming, roll, first, second));

        var shifted = new Port(second.endpoint(), second.center().add(0, 0.25, 0), second.normal(),
                second.tangent(), second.size());
        var fractional = ShipDocking.capture(first, shifted, new Quaterniond(), Vec3.ZERO, Vec3.ZERO, limits);
        assertNotNull(fractional);
        assertNull(ShipDocking.merge(primary, incoming, fractional, first, shifted));
    }

    @Test
    public void releaseRestoresOriginalStageOrderAndIdentity() {
        var first = new Identity(UUID.randomUUID(), Optional.empty(), Optional.empty());
        var second = new Identity(UUID.randomUUID(), Optional.of(UUID.randomUUID()), Optional.empty());
        var a = new ShipDockingState(List.of(new StageBlock(bits(0, 1), 0, 2, 0, Optional.of(first))), Set.of());
        var b = new ShipDockingState(List.of(new StageBlock(bits(0, 1), 0, 3, 1, Optional.of(second))), Set.of());
        var merged = a.merge(b, 2).release(new PortKey(2, 0));
        assertEquals(3, merged.nextStage(1));
        assertEquals(second, merged.rootIn(List.of(3, 2)).identity().orElseThrow());
        var incoming = merged.project(new int[]{-1, -1, 0, 1}, 0, 5);
        assertEquals(3, incoming.stageCount());
        assertEquals(1, incoming.stage());
        assertEquals(0, incoming.mapStage(2));
        assertEquals(2, incoming.mapStage(4));
        assertEquals(Set.of(new PortKey(0, 0)), incoming.state().released());
        assertEquals(2, merged.project(new int[]{-1, -1, 0, 1}, 4, 5).stage());
    }

    @Test
    public void stageMembershipSurvivesDockingSaveAndReorderedProjection() {
        var first = new Identity(UUID.randomUUID(), Optional.empty(), Optional.empty());
        var second = new Identity(UUID.randomUUID(), Optional.empty(), Optional.empty());
        var a = ShipDockingState.EMPTY.snapshot(chain(70, 2), 0, first);
        var b = ShipDockingState.EMPTY.snapshot(chain(70, 3), 1, second);
        var merged = a.merge(b, 70).release(new PortKey(139, 0));
        var codec = ShipDockingState.Serialization.CODEC;
        var restored = codec.parse(NbtOps.INSTANCE, codec.encodeStart(NbtOps.INSTANCE, merged).getOrThrow()).getOrThrow();
        assertEquals(merged, restored);
        assertEquals(70, restored.stages().getFirst().parts().cardinality());
        assertEquals(70, restored.stages().getLast().parts().cardinality());
        assertEquals(0, restored.actionStage(64, -1));
        assertEquals(3, restored.actionStage(139, -1));

        var indices = ShipDesign.indices(140, List.of(70, 139, 0, 64, 69));
        var fragment = restored.project(indices, 1, 5);
        assertEquals(bits(2, 3, 4), fragment.state().stages().getFirst().parts());
        assertEquals(bits(0, 1), fragment.state().stages().getLast().parts());
        assertEquals(0, fragment.state().stages().getLast().root());
        assertEquals(second, fragment.state().rootIn(List.of(0, 1)).identity().orElseThrow());

        var incoming = restored.project(ShipDesign.indices(140, IntStream.range(70, 140).boxed().toList()), 1, 5);
        assertEquals(3, incoming.stageCount());
        assertEquals(1, incoming.stage());
        assertEquals(Set.of(new PortKey(69, 0)), incoming.state().released());
    }

    @Test
    public void damageFollowsHighNodeIndicesThroughDockingAndSeparation() {
        var a = new ShipFlightState(chain(65, 1), ShipPropellantData.EMPTY, bits(1, 63, 64, 200), 0);
        var b = new ShipFlightState(chain(67, 1), ShipPropellantData.EMPTY, bits(0, 64, 65), 0);
        var layout = chain(132, 2);
        var merged = a.docked(layout, b, a.dockedDamage(b));
        assertEquals(bits(1, 63, 64, 65, 129, 130), merged.damagedParts());
        assertFalse(merged.isDamaged(2));

        var members = IntStream.iterate(131, node -> node >= 63, node -> node - 1).boxed().toList();
        var fragment = layout.subset(members, Set.of(), new BlockPos(131, 0, 0));
        var separated = merged.fragment(fragment, members, ShipDesign.indices(132, members), 0);
        assertEquals(bits(1, 2, 66, 67, 68), separated.damagedParts());

        var saved = new CompoundTag();
        saved.putLongArray("DamagedParts", merged.damagedParts().toLongArray());
        assertEquals(merged.damagedParts(), AssembledShipEntity.readDamagedParts(saved));
        saved.putLong("DamagedParts", -1);
        assertTrue(AssembledShipEntity.readDamagedParts(saved).isEmpty());
    }

    @Test
    public void flightCodecsAcceptMergedLimitsWhileBlueprintsKeepAssemblyLimits() {
        var layout = chain(ShipDesign.MAX_MERGED_PARTS, ShipDesign.MAX_MERGED_STAGES);
        assertTrue(layout.isValidLayout());
        assertFalse(layout.isValid());
        var tag = ShipDesign.FLIGHT_CODEC.encodeStart(NbtOps.INSTANCE, layout).getOrThrow();
        assertEquals(layout, ShipDesign.FLIGHT_CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow());
        assertTrue(ShipDesign.CODEC.parse(NbtOps.INSTANCE, tag).error().isPresent());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            ShipDesign.FLIGHT_STREAM_CODEC.encode(buffer, layout);
            assertEquals(layout.flightLayoutBytes(RegistryAccess.EMPTY), buffer.readableBytes());
            var synced = ShipDesign.FLIGHT_STREAM_CODEC.decode(buffer);
            assertEquals(layout.stageCount(), synced.stageCount());
            assertEquals(layout.links(), synced.links());
            assertEquals(layout.nodes().stream().map(ShipDesign.Node::pose).toList(),
                    synced.nodes().stream().map(ShipDesign.Node::pose).toList());
            assertEquals(0, buffer.readableBytes());
            assertThrows(EncoderException.class, () -> ShipDesign.BLUEPRINT_STREAM_CODEC.encode(buffer, layout));

            buffer.clear();
            var pose = new PartPose(new BlockPos(-345, 4_096, 7_250), OctahedralGroup.IDENTITY);
            PartPose.STREAM_CODEC.encode(buffer, pose);
            assertEquals(pose, PartPose.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    private static BitSet bits(int... indices) {
        var bits = new BitSet();
        for (int index : indices) {
            bits.set(index);
        }
        return bits;
    }

    private static ShipDesign chain(int count, int stages) {
        var sample = BakedPart.bake(Map.of(BlockPos.ZERO, Blocks.STONE.defaultBlockState())).orElseThrow();
        var pod = new BakedPart(sample.geometry(), sample.properties(), sample.centerOfMass(), sample.inertia(),
                Set.of(PartType.COMMAND_POD), 1, List.of());
        var nodes = new ArrayList<ShipDesign.Node>();
        var links = new ArrayList<ShipDesign.Link>();
        for (int index = 0; index < count; index++) {
            var pose = new PartPose(new BlockPos(index * 4, 0, 0), OctahedralGroup.IDENTITY);
            nodes.add(new ShipDesign.Node(index == 0 ? pod : sample, pose, -1));
            if (index > 0) {
                var first = new Endpoint(index - 1, -1, Direction.EAST, new Vec3(1, 0.5, 0.5));
                var second = new Endpoint(index, -1, Direction.WEST, new Vec3(0, 0.5, 0.5));
                links.add(new ShipDesign.Link(first, second, true, false, -1));
            }
        }
        return new ShipDesign(nodes, links, stages);
    }

    private static Port port(Vec3 center, Direction normal, int size) {
        return new Port(new Endpoint(0, 0, normal, center), center, normal, Direction.UP, size);
    }

    private static Port port(ShipDesign design) {
        var connector = design.nodes().getFirst().part().connectors().getFirst();
        return port(connector.center(), connector.normal(), connector.sizeQuarters());
    }

    private static ShipDesign hullShip(Direction normal) {
        var sample = BakedPart.bake(Map.of(BlockPos.ZERO, Blocks.IRON_BLOCK.defaultBlockState(),
                new BlockPos(1, 1, 1), Blocks.IRON_BLOCK.defaultBlockState())).orElseThrow();
        var center = new Vec3(1, 1, 1).relative(normal, 1);
        var connector = new PartConnector(PartConnector.Kind.DOCKING, center, normal, 2, 2);
        var part = new BakedPart(sample.geometry(), sample.properties(), sample.centerOfMass(), sample.inertia(),
                Set.of(PartType.COMMAND_POD), 1, List.of(connector));
        var anchor = new Vec3(0.5, 0.5, 0.5).with(normal.getAxis(),
                normal.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 0 : 2);
        var origin = BlockPos.containing(anchor.relative(normal.getOpposite(), 0.5));
        var stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
        var hull = HullRegion.capture(Map.of(origin, stairs), origin);
        return new ShipDesign(List.of(new ShipDesign.Node(part, PartPose.ROOT, -1), ShipDesign.Node.hull(hull, origin)),
                List.of(new ShipDesign.Link(new Endpoint(0, -1, normal.getOpposite(), anchor),
                        new Endpoint(1, -1, normal, anchor.subtract(Vec3.atLowerCornerOf(origin))), true, false, -1)), 1);
    }
}

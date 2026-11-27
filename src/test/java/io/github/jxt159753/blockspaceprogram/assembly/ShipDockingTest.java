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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtOps;
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
        var a = new ShipDockingState(List.of(new StageBlock(0b11, 0, 2, 0, Optional.of(first))), Set.of());
        var b = new ShipDockingState(List.of(new StageBlock(0b11, 0, 3, 1, Optional.of(second))), Set.of());
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

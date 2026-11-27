package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.math.OctahedralGroup;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign.Endpoint;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDocking.CaptureLimits;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDocking.Port;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDocking.PortKey;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDockingState.Identity;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDockingState.StageBlock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.junit.Test;

import static org.junit.Assert.*;

public class ShipDockingTest {
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
}

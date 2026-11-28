package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.Test;

import static io.github.jxt159753.blockspaceprogram.assembly.RcsAllocation.Method.LEAST_SQUARES;
import static io.github.jxt159753.blockspaceprogram.assembly.RcsAllocation.Method.PROJECTION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RcsAllocationTest {
    private static final Vector3dc ZERO = new Vector3d();
    private static final double EPSILON = 0.25;

    @Test
    public void symmetricTranslationHasNoMoment() {
        var jets = new ArrayList<RcsAllocation.Jet>();
        for (int x : new int[]{-1, 1}) {
            for (int y : new int[]{-1, 1}) {
                for (int direction : new int[]{-1, 1}) {
                    jets.add(jet(x, y, 0, 0, 0, direction * 250));
                }
            }
        }
        for (var method : RcsAllocation.Method.values()) {
            var result = RcsAllocation.allocate(method, jets, new Vector3d(0, 0, 700), ZERO, 0);
            assertEquals(700, result.force().z(), EPSILON);
            assertEquals(0, result.torque().length(), EPSILON);
            assertEquals(0.70, result.fuelFlow(), 0.001);
            assertBounded(result);
        }
    }

    @Test
    public void wheelCancelsOffsetThrustWhenThereIsCapacity() {
        var jets = List.of(jet(0, 1, 0, 1_000, 0, 0));
        var force = new Vector3d(1_000, 0, 0);
        var projection = RcsAllocation.allocate(PROJECTION, jets, force, ZERO, 1_500);
        assertEquals(1_000, projection.force().x(), EPSILON);
        assertEquals(-1_000, projection.torque().z(), EPSILON);
        var withWheel = RcsAllocation.allocate(LEAST_SQUARES, jets, force, ZERO, 1_500);
        assertEquals(1_000, withWheel.force().x(), EPSILON);
        assertEquals(0, withWheel.torque().length(), EPSILON);
        assertEquals(1_000, withWheel.wheelTorque().z(), EPSILON);
        var withoutWheel = RcsAllocation.allocate(LEAST_SQUARES, jets, force, ZERO, 0);
        assertEquals(200, withoutWheel.force().x(), EPSILON);
        assertEquals(-200, withoutWheel.torque().z(), EPSILON);
        assertEquals(-1_000, RcsAllocation.allocate(PROJECTION, jets, force, ZERO, 0).torque().z(), EPSILON);
    }

    @Test
    public void coplanarJetsCanRotateWithoutTranslating() {
        var jets = List.of(jet(0, 1, 0, 1_000, 0, 0), jet(0, -1, 0, -800, 0, 0));
        var torque = new Vector3d(0, 0, -1_000);
        var result = RcsAllocation.allocate(LEAST_SQUARES, jets, ZERO, torque, 0);
        assertEquals(0, result.force().length(), EPSILON);
        assertEquals(-1_000, result.torque().z(), EPSILON);
        assertEquals(111.11, RcsAllocation.allocate(PROJECTION, jets, ZERO, torque, 0).force().x(), EPSILON);
        assertBounded(result);
    }

    @Test
    public void collinearJetsDoNotFireForUnreachableAxes() {
        var jets = List.of(jet(-1, 0, 0, 800, 0, 0), jet(1, 0, 0, -800, 0, 0));
        for (var method : RcsAllocation.Method.values()) {
            var result = RcsAllocation.allocate(method, jets, new Vector3d(0, 700, 0), new Vector3d(0, 0, 300), 0);
            assertEquals(0, result.force().length(), EPSILON);
            assertEquals(0, result.torque().length(), EPSILON);
            assertEquals(0, result.fuelFlow(), EPSILON);
        }
    }

    @Test
    public void saturatedAxesCanChangeAnAsymmetricDiagonalCommand() {
        var jets = List.of(jet(0, 0, 0, 800, 0, 0), jet(0, 0, 0, 0, 300, 0));
        var requested = new Vector3d(2_000, 2_000, 0);
        for (var method : RcsAllocation.Method.values()) {
            var result = RcsAllocation.allocate(method, jets, requested, ZERO, 0);
            assertEquals(800, result.force().x(), EPSILON);
            assertEquals(300, result.force().y(), EPSILON);
            assertEquals(24.44, Math.toDegrees(requested.angle(result.force())), 0.01);
            var axial = RcsAllocation.allocate(method, jets, new Vector3d(2_000, 0, 0), ZERO, 0);
            assertEquals(0, axial.force().y(), EPSILON);
            assertBounded(result);
        }
    }

    @Test
    public void wheelAloneAvoidsFuelUse() {
        var jets = List.of(jet(0, 1, 0, 1_000, 0, 0), jet(0, -1, 0, -800, 0, 0));
        var result = RcsAllocation.allocate(LEAST_SQUARES, jets, ZERO, new Vector3d(0, 0, -700), 1_500);
        assertEquals(0, result.fuelFlow(), 0);
        assertEquals(-700, result.wheelTorque().z(), 0);
    }

    private static RcsAllocation.Jet jet(double x, double y, double z, double fx, double fy, double fz) {
        var force = new Vector3d(fx, fy, fz);
        return new RcsAllocation.Jet(force, new Vector3d(x, y, z).cross(force), force.length() / 1_000);
    }

    private static void assertBounded(RcsAllocation.Result result) {
        for (double opening : result.openings()) {
            assertTrue(opening >= 0 && opening <= 1);
        }
    }
}

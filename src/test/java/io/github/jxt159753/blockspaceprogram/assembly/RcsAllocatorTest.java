package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RcsAllocatorTest {
    private static final Vector3dc ZERO = new Vector3d();
    private static final double EPSILON = 0.25;

    @Test
    public void symmetricTranslationHasNoMoment() {
        var jets = new ArrayList<RcsAllocator.Jet>();
        for (int x : new int[]{-1, 1}) {
            for (int y : new int[]{-1, 1}) {
                for (int direction : new int[]{-1, 1}) {
                    jets.add(jet(x, y, 0, 0, 0, direction * 250));
                }
            }
        }
        var result = RcsAllocator.allocate(jets, new Vector3d(0, 0, 700), ZERO, 0);
        assertEquals(700, result.force().z(), EPSILON);
        assertEquals(0, result.torque().length(), 1e-4);
        assertEquals(0.70, result.fuelFlow(), 0.001);
        assertBounded(result);
    }

    @Test
    public void wheelCancelsOffsetThrustOrSolverReturnsMinimumError() {
        var jets = List.of(jet(0, 1, 0, 1_000, 0, 0));
        var force = new Vector3d(1_000, 0, 0);
        var withWheel = RcsAllocator.allocate(jets, force, ZERO, 1_500);
        assertEquals(1_000, withWheel.force().x(), EPSILON);
        assertEquals(0, withWheel.torque().length(), 1e-4);
        assertEquals(1_000, withWheel.wheelTorque().z(), EPSILON);
        var withoutWheel = RcsAllocator.allocate(jets, force, ZERO, 0);
        assertEquals(200, withoutWheel.force().x(), EPSILON);
        assertEquals(-200, withoutWheel.torque().z(), EPSILON);
        assertEquals(0, RcsAllocator.pureThrust(jets, new Vector3d(1, 0, 0), 0), 0);
    }

    @Test
    public void coplanarJetsCanRotateWithoutTranslating() {
        var jets = List.of(jet(0, 1, 0, 1_000, 0, 0), jet(0, -1, 0, -800, 0, 0));
        var torque = new Vector3d(0, 0, -1_000);
        var result = RcsAllocator.allocate(jets, ZERO, torque, 0);
        assertEquals(0, result.force().length(), EPSILON);
        assertEquals(-1_000, result.torque().z(), EPSILON);
        assertBounded(result);
    }

    @Test
    public void collinearJetsDoNotFireForUnreachableAxes() {
        var jets = List.of(jet(-1, 0, 0, 800, 0, 0), jet(1, 0, 0, -800, 0, 0));
        var result = RcsAllocator.allocate(jets, new Vector3d(0, 700, 0), new Vector3d(0, 0, 300), 0);
        assertEquals(0, result.force().length(), 0);
        assertEquals(0, result.torque().length(), 0);
        assertEquals(0, result.fuelFlow(), 0);
    }

    @Test
    public void saturatedSingleAxisAndDiagonalRetainDirection() {
        var jets = List.of(jet(0, 0, 0, 800, 0, 0), jet(0, 0, 0, 0, 300, 0));
        var diagonal = RcsAllocator.allocate(jets, new Vector3d(2_000, 2_000, 0), ZERO, 0);
        assertEquals(300, diagonal.force().x(), EPSILON);
        assertEquals(diagonal.force().x(), diagonal.force().y(), 1e-3);
        assertEquals(0, diagonal.force().z(), 0);
        var axial = RcsAllocator.allocate(jets, new Vector3d(2_000, 0, 0), ZERO, 0);
        assertEquals(800, axial.force().x(), EPSILON);
        assertEquals(0, axial.force().y(), 1e-3);
        assertBounded(diagonal);
        assertBounded(axial);
    }

    @Test
    public void wheelAloneAvoidsFuelUse() {
        var jets = List.of(jet(0, 1, 0, 1_000, 0, 0), jet(0, -1, 0, -800, 0, 0));
        var result = RcsAllocator.allocate(jets, ZERO, new Vector3d(0, 0, -700), 1_500);
        assertEquals(0, result.fuelFlow(), 0);
        assertEquals(-700, result.wheelTorque().z(), 0);
    }

    @Test
    public void unreachableDiagonalUsesLeastSquaresAcrossAvailableAxes() {
        var jets = List.of(jet(0, 0, 0, 800, 0, 0), jet(0, 0, 0, 0, 0, 300));
        var result = RcsAllocator.allocate(jets, new Vector3d(2_000, 2_000, 200), ZERO, 0);
        assertEquals(800, result.force().x(), EPSILON);
        assertEquals(0, result.force().y(), 0);
        assertEquals(200, result.force().z(), EPSILON);
    }

    @Test
    public void wheelLimitCanReducePureThrust() {
        var jets = List.of(jet(0, 1, 0, 1_000, 0, 0));
        var direction = new Vector3d(1, 0, 0);
        var result = RcsAllocator.allocate(jets, new Vector3d(2_000, 0, 0), ZERO, 300);
        assertEquals(300, result.force().x(), EPSILON);
        assertEquals(0, result.torque().length(), 1e-3);
        assertEquals(result.force().x(), RcsAllocator.pureThrust(jets, direction, 300), 1e-6);
    }

    private static RcsAllocator.Jet jet(double x, double y, double z, double fx, double fy, double fz) {
        var force = new Vector3d(fx, fy, fz);
        double thrust = force.length();
        return new RcsAllocator.Jet(new Vector3d(x, y, z), force.div(thrust), thrust, thrust / 1_000);
    }

    private static void assertBounded(RcsAllocator.Result result) {
        for (double opening : result.openings()) {
            assertTrue(opening >= 0 && opening <= 1);
        }
    }
}

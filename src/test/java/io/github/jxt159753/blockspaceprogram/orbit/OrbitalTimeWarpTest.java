package io.github.jxt159753.blockspaceprogram.orbit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

public class OrbitalTimeWarpTest {
    private static final OrbitalSystem SYSTEM = OrbitalSystem.M3;

    @Test
    public void stopsOnTheLastTickStrictlyBeforeAnEvent() {
        assertEquals(19, OrbitalTimeWarp.stepBefore(40, 100_000, 3.0));
        assertEquals(20, OrbitalTimeWarp.stepBefore(40, 100_000, 3.025));
        assertEquals(1, OrbitalTimeWarp.stepBefore(40, 100_000, 2.05));
        assertEquals(1, OrbitalTimeWarp.stepBefore(40, 100_000, 1.5));
    }

    @Test
    public void findsAnEncounterEntirelyBetweenTheSamples() {
        var approach = new OrbitalTimeWarp.Proximity(
                time -> new OrbitalState(new Vec3(950 + 20 * time * time, 0, 0), new Vec3(40 * time, 0, 0)),
                1_000, 20, 40);
        // Enters the sphere earlier, then slows to the entry limit at -0.5 s.
        assertEquals(-0.5, approach.firstInside(-3, 3), 0.001);
    }

    @Test
    public void aFastFlybyDoesNotStopWarp() {
        var approach = new OrbitalTimeWarp.Proximity(
                time -> new OrbitalState(new Vec3(1_420 * time, 0, 0), new Vec3(1_420, 0, 0)),
                1_000, 40, 0);
        assertEquals(Double.POSITIVE_INFINITY, approach.firstInside(-2, 2), 0);
    }

    @Test
    public void detectsDescentOnTheLegAfterASphereOfInfluenceChange() {
        var mother = new Orbit(OrbitalSystem.MOTHER_PLANET,
                OrbitElements.fromSemiMajorAxis(70_000, 0, 0, 0, 0, 0, 0));
        var moon = new Orbit(OrbitalSystem.MOON,
                OrbitElements.fromSemiMajorAxis(14_000, 0.13, 0, 0, 0, Math.PI, 100));
        double mu = SYSTEM.body(moon.center()).gravitationalParameter();
        double to = 100 + moon.elements().period(mu);
        var radii = Map.of(mother.center(), 65_000.0, moon.center(), 13_000.0);
        double event = OrbitalTimeWarp.firstDescent(List.of(mother, moon), radii, Vec3.ZERO, 0, to);
        assertTrue(event > 100 && event < to);
        var state = SYSTEM.relativeState(moon, event);
        assertEquals(13_000, state.position().length(), 1e-7);
        assertTrue(state.position().dot(state.velocity()) < 0);
        assertTrue(SYSTEM.relativeState(moon, to).position().length() > 13_000);
    }

    @Test
    public void detectsAnOffsetRegionalMemberDescendingWhileTheOriginStaysAboveTheShell() {
        var reference = new Orbit(OrbitalSystem.MOTHER_PLANET,
                OrbitElements.fromSemiMajorAxis(65_200, 0, 0, 0, 0, 0, 0));
        double period = reference.elements().period(SYSTEM.body(reference.center()).gravitationalParameter());
        var radii = Map.of(reference.center(), 65_000.0);
        assertEquals(Double.POSITIVE_INFINITY,
                OrbitalTimeWarp.firstDescent(List.of(reference), radii, Vec3.ZERO, 0, period), 0);

        Vec3 offset = new Vec3(300, 0, 0);
        double event = OrbitalTimeWarp.firstDescent(List.of(reference), radii, offset, 0, period);
        assertTrue(event > 0 && event < period / 2);
        assertEquals(65_000, SYSTEM.relativeState(reference, event).position().add(offset).length(), 0.01);
    }
}

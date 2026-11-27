package io.github.jxt159753.blockspaceprogram.orbit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.Test;

public class OrbitalPlannerTest {
    private static final OrbitalSystem SYSTEM = OrbitalSystem.M3;
    private static final Orbit LOW_ORBIT = new Orbit(OrbitalSystem.MOTHER_PLANET,
            OrbitElements.fromSemiMajorAxis(70_000, 0, 0, 0, 0, 0, 0));

    @Test
    public void plansAWindowAndFindsTheMoonEncounterAfterPatchingTheOrbit() {
        var moon = SYSTEM.body(OrbitalSystem.MOON);
        var window = OrbitalPlanner.hohmann(SYSTEM, LOW_ORBIT, moon.orbit(), 0);
        assertNotNull(window);
        assertEquals(0, window.currentPhase(), 0);
        assertEquals(97, Math.toDegrees(window.departurePhase()), 1);

        var node = window.node();
        assertEquals(208.7145, node.deltaV().x, 1e-4);
        assertTrue(node.seconds() > OrbitalPlanner.NODE_LEAD_SECONDS);
        Orbit transfer = node.preview(SYSTEM, LOW_ORBIT, 0);
        assertEquals(70_000, transfer.elements().periapsis(), 1e-6);
        assertEquals(360_000, transfer.elements().apoapsis(), 1e-6);

        double end = node.seconds() + OrbitalPlanner.PREDICTION_SECONDS;
        var path = SYSTEM.forecast(transfer, node.seconds(), end);
        var closest = OrbitalPlanner.closestApproach(path, time -> SYSTEM.bodyState(moon.id(), time), node.seconds(), end);
        assertTrue(closest.distance() < SYSTEM.sphereOfInfluence(moon.id()));
        assertTrue(closest.seconds() > node.seconds() + 1_200 && closest.seconds() < node.seconds() + 2_000);
        assertTrue(closest.relativeSpeed() > 0);
        assertEquals(moon.id(), SYSTEM.advance(transfer, node.seconds(), closest.seconds()).center());
        assertTrue(closest.distance() < moon.radius());
        var surface = OrbitalPlanner.surfaceContact(path, time -> SYSTEM.bodyState(moon.id(), time),
                node.seconds(), closest, moon.radius());
        assertEquals(moon.radius(), surface.distance(), 0.001);
        assertTrue(surface.seconds() < closest.seconds());
    }

    @Test
    public void appliesProgradeNormalAndRadialAtTheSamePosition() {
        var node = new ManeuverNode(0, new Vec3(30, 20, 10));
        var before = SYSTEM.relativeState(LOW_ORBIT, 0);
        var after = SYSTEM.relativeState(node.preview(SYSTEM, LOW_ORBIT, 0), 0);
        assertEquals(0, before.position().distanceTo(after.position()), 1e-8);
        assertEquals(0, new Vec3(10, 30, 20).distanceTo(after.velocity().subtract(before.velocity())), 1e-8);
    }

    @Test
    public void reportsDistanceTimeAndRelativeVelocityForTwoMovingObjects() {
        var closest = OrbitalPlanner.closestApproach(time -> new OrbitalState(new Vec3(15 * time, 0, 0), new Vec3(15, 0, 0)),
                time -> new OrbitalState(new Vec3(10 * time + 2, 27, 0), new Vec3(10, 0, 0)), 0, 3_600);
        assertEquals(0.4, closest.seconds(), 0.002);
        assertEquals(27, closest.distance(), 1e-5);
        assertEquals(5, closest.relativeSpeed(), 1e-9);
    }
}

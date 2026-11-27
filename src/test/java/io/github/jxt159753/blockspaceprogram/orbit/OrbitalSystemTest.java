package io.github.jxt159753.blockspaceprogram.orbit;

import static io.github.jxt159753.blockspaceprogram.orbit.OrbitElementsTest.assertState;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.Test;

public class OrbitalSystemTest {
    private static final OrbitalSystem SYSTEM = OrbitalSystem.legacy();
    private static final CelestialBody MOTHER = SYSTEM.body(OrbitalSystem.MOTHER_PLANET);
    private static final CelestialBody MOON = SYSTEM.body(OrbitalSystem.MOON);
    private static final double MOON_SOI = SYSTEM.sphereOfInfluence(MOON.id());

    @Test
    public void m3BodiesSupportALowOrbitToMoonTransfer() {
        assertEquals(60_000, MOTHER.radius(), 0);
        assertEquals(3.528e10, MOTHER.gravitationalParameter(), 0);
        assertEquals(12_000, MOON.radius(), 0);
        assertEquals(2.304e8, MOON.gravitationalParameter(), 0);
        assertEquals(MOTHER.id(), MOON.orbit().center());
        assertEquals(360_000, MOON.orbit().elements().semiMajorAxis(), 0);
        assertEquals(0, MOON.orbit().elements().eccentricity(), 0);
        assertEquals(0, MOON.orbit().elements().epochSeconds(), 0);
        assertTrue(MOON_SOI > MOON.radius());

        double lowRadius = 70_000;
        double moonRadius = MOON.orbit().elements().semiMajorAxis();
        double transferAxis = (lowRadius + moonRadius) / 2;
        double transferTime = Math.PI * Math.sqrt(Math.pow(transferAxis, 3) / MOTHER.gravitationalParameter());
        double moonPeriod = MOON.orbit().elements().period(MOTHER.gravitationalParameter());
        double departure = moonPeriod / 2 - transferTime;
        assertEquals(27.8, transferTime / 60, 0.1);

        Orbit transfer = new Orbit(MOTHER.id(), OrbitElements.fromSemiMajorAxis(
                transferAxis, (moonRadius - lowRadius) / (moonRadius + lowRadius), 0, 0, 0, 0, departure));
        Orbit arrival = SYSTEM.advance(transfer, departure, departure + transferTime);
        assertEquals(MOON.id(), arrival.center());
        assertPatch(transfer, arrival, MOON_SOI, MOON.id());
        assertFalse(SYSTEM.mayCross(new Orbit(MOTHER.id(), OrbitElements.fromSemiMajorAxis(
                lowRadius, 0, 0, 0, 0, 0, 0))));
    }

    @Test
    public void detectsAnEntireFlybyBetweenOutsideSamples() {
        Orbit approach = new Orbit(MOTHER.id(), OrbitElements.fromSemiMajorAxis(
                345_000, 0, Math.PI, 0, 0, -0.5, 0));
        double end = 1_000;
        assertTrue(distanceToMoon(approach, 0) > MOON_SOI);
        assertTrue(distanceToMoon(approach, end) > MOON_SOI);

        Orbit flyby = SYSTEM.advance(approach, 0, 285);
        assertEquals(MOON.id(), flyby.center());
        assertTrue(flyby.elements().eccentricity() > 1);
        assertPatch(approach, flyby, MOON_SOI, MOON.id());
        Orbit departure = SYSTEM.advance(flyby, 285, end);
        assertEquals(MOTHER.id(), departure.center());
        assertPatch(flyby, departure, MOON_SOI, MOON.id());

        Orbit complete = SYSTEM.advance(approach, 0, end);
        assertEquals(MOTHER.id(), complete.center());
        assertTrue(complete.elements().epochSeconds() > flyby.elements().epochSeconds());
        OrbitalState splitInterval = SYSTEM.absoluteState(departure, end);
        OrbitalState fullInterval = SYSTEM.absoluteState(complete, end);
        assertEquals(0, splitInterval.position().distanceTo(fullInterval.position()), 0.1);
        assertEquals(0, splitInterval.velocity().distanceTo(fullInterval.velocity()), 1e-3);
    }

    private static double distanceToMoon(Orbit ship, double time) {
        return SYSTEM.absoluteState(ship, time).position().distanceTo(SYSTEM.bodyState(MOON.id(), time).position());
    }

    private static void assertPatch(Orbit before, Orbit after, double radius, ResourceLocation body) {
        double time = after.elements().epochSeconds();
        OrbitalState worldBefore = SYSTEM.absoluteState(before, time);
        OrbitalState worldAfter = SYSTEM.absoluteState(after, time);
        assertState(worldBefore, worldAfter);
        assertEquals(radius, worldAfter.position().distanceTo(SYSTEM.bodyState(body, time).position()), 2);
    }
}

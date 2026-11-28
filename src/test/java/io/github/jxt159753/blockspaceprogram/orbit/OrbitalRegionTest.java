package io.github.jxt159753.blockspaceprogram.orbit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

public class OrbitalRegionTest {
    @Test
    public void changingRegionPreservesInertialStateAtTheSameTime() {
        var system = OrbitalSystem.legacy();
        double now = 42;
        var first = new Orbit(OrbitalSystem.MOTHER_PLANET,
                OrbitElements.fromSemiMajorAxis(70_000, 0, 0, 0, 0, 0, 0));
        var second = new Orbit(OrbitalSystem.MOTHER_PLANET,
                OrbitElements.fromSemiMajorAxis(70_020, 0.001, 0, 0, 0, 0.01, 0));
        var source = new OrbitalRegion.Frame(system, first, OrbitalRegion.slotOrigin(1), now);
        var target = new OrbitalRegion.Frame(system, second, OrbitalRegion.ORIGIN, now);
        var local = new OrbitalState(source.worldOrigin().add(25, 30, -12), new Vec3(-4, 2, 5));
        var absolute = source.absolute(local);
        var translated = local.add(source.shiftTo(target));
        var after = target.absolute(translated);

        assertEquals(0, absolute.position().distanceTo(after.position()), 1e-8);
        assertEquals(0, absolute.velocity().distanceTo(after.velocity()), 1e-8);
        assertEquals(0, target.local(absolute).position().distanceTo(translated.position()), 1e-8);
    }

    @Test
    public void slotOriginsAreDistinctAndKeepTheOriginalRegionOrigin() {
        var slots = new HashSet<Vec3>();
        for (int index = 0; index < 100; index++) {
            assertTrue(slots.add(OrbitalRegion.slotOrigin(index)));
        }
        assertEquals(OrbitalRegion.ORIGIN, OrbitalRegion.slotOrigin(0));
    }

    @Test
    public void requiresAWithinRangePairAndRejectsAnyFastNearbyPair() {
        var pilot = new OrbitalState(Vec3.ZERO, new Vec3(0, 710, 0));
        var atBoundary = new OrbitalState(new Vec3(1_000, 0, 0), pilot.velocity());
        var approach = new OrbitalState(new Vec3(999, 0, 0), new Vec3(0, 750, 0));
        var reverse = new OrbitalState(new Vec3(42, 0, 0), new Vec3(0, -710, 0));

        assertFalse(OrbitalRegion.canMerge(List.of(pilot), List.of(atBoundary), 1_000, 40));
        assertTrue(OrbitalRegion.canMerge(List.of(pilot), List.of(approach), 1_000, 40));
        assertFalse(OrbitalRegion.canMerge(List.of(pilot), List.of(approach, reverse), 1_000, 40));
    }
}

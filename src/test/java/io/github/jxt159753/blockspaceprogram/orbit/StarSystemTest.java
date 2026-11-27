package io.github.jxt159753.blockspaceprogram.orbit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.jxt159753.blockspaceprogram.orbit.StarSystem.Shape;
import io.github.jxt159753.blockspaceprogram.orbit.StarSystem.Spacing;
import org.junit.Test;

public class StarSystemTest {
    @Test
    public void samplesAllPresets() {
        for (Spacing spacing : Spacing.values()) {
            for (Shape shape : Shape.values()) {
                for (long seed = 0; seed < 1_000; seed++) {
                    var profile = StarSystem.generate(spacing, shape, seed);
                    var orbit = profile.moonOrbit();
                    var system = OrbitalSystem.withMoonOrbit(orbit);
                    assertEquals(profile, StarSystem.generate(spacing, shape, seed));
                    assertTrue(system.sphereOfInfluence(OrbitalSystem.MOON) > 12_000);
                    assertTrue(orbit.semiMajorAxis() >= spacing.minAxis && orbit.semiMajorAxis() <= spacing.maxAxis);
                    assertTrue(orbit.meanAnomalyAtEpoch() >= 0 && orbit.meanAnomalyAtEpoch() < 2 * Math.PI);
                    if (shape == Shape.REGULAR) {
                        assertEquals(0, orbit.eccentricity(), 0);
                        assertEquals(0, orbit.inclination(), 0);
                    } else {
                        assertTrue(orbit.eccentricity() >= 0 && orbit.eccentricity() <= 0.05);
                        assertTrue(orbit.inclination() >= 0 && orbit.inclination() <= Math.toRadians(5));
                        assertTrue(orbit.longitudeOfAscendingNode() >= 0 && orbit.longitudeOfAscendingNode() < 2 * Math.PI);
                        assertTrue(orbit.argumentOfPeriapsis() >= 0 && orbit.argumentOfPeriapsis() < 2 * Math.PI);
                    }
                    long minutes = Math.round(profile.hohmannSeconds(system) / 60);
                    assertTrue(switch (spacing) {
                        case NEAR -> minutes >= 15 && minutes <= 19;
                        case MEDIUM -> minutes >= 25 && minutes <= 31;
                        case FAR -> minutes >= 40 && minutes <= 54;
                    });
                }
            }
        }
    }

    @Test
    public void reproducesThePrototypeFromTheWorldSeed() {
        long seed = StarSystem.seedFromWorld(90_000_000_000_053L);
        assertEquals(-3_468_122_787_463_884_361L, seed);
        var expected = new OrbitElements(228476.2611270153, 0.0202712073756173, 0.047587784961617176,
                1.7175573403196582, 1.2254123308558649, 3.536671299755473, 0.0);
        assertEquals(expected, StarSystem.generate(Spacing.NEAR, Shape.NATURAL, seed).moonOrbit());
    }
}

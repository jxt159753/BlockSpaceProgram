package io.github.jxt159753.blockspaceprogram.orbit;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

public class OrbitElementsTest {
    private static final double MU = 3.528e10;

    @Test
    public void gameMapPositionsMatchTheSharedBrowserFixtures() throws IOException {
        var gson = new Gson();
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/orbit/kepler-position-cases.json"),
                StandardCharsets.UTF_8)) {
            for (var entry : JsonParser.parseReader(reader).getAsJsonArray()) {
                var fixture = entry.getAsJsonObject();
                var orbit = gson.fromJson(fixture.get("orbit"), OrbitElements.class);
                double[] expected = gson.fromJson(fixture.get("position"), double[].class);
                var position = orbit.stateAt(fixture.get("mu").getAsDouble(), fixture.get("seconds").getAsDouble()).position();
                assertEquals(fixture.get("name").getAsString(), 0,
                        position.distanceTo(new Vec3(expected[0], expected[1], expected[2])), 0.01);
            }
        }
    }

    @Test
    public void lowCircularOrbitMatchesPeriodAndQuarterOrbit() {
        double radius = 70_000;
        double epoch = 120;
        OrbitElements orbit = OrbitElements.fromSemiMajorAxis(radius, 0, 0, 0, 0, 0, epoch);
        double circularSpeed = Math.sqrt(MU / radius);
        double expectedPeriod = 2 * Math.PI * radius / circularSpeed;
        assertEquals(619.54, orbit.period(MU), 0.02);
        assertEquals(expectedPeriod, orbit.period(MU), 1e-10);
        assertState(new OrbitalState(new Vec3(0, radius, 0), new Vec3(-circularSpeed, 0, 0)),
                orbit.stateAt(MU, epoch + expectedPeriod / 4));
        assertState(orbit.stateAt(MU, epoch), orbit.stateAt(MU, epoch + expectedPeriod));
        assertState(orbit.stateAt(MU, epoch), orbit.stateAt(MU, epoch - expectedPeriod));
    }

    @Test
    public void eccentricOrbitArrivesAtTheExpectedAnomaly() {
        double a = 200_000;
        double e = 0.71;
        double n = Math.sqrt(MU / Math.pow(a, 3));
        OrbitElements orbit = OrbitElements.fromSemiMajorAxis(a, e, 0, 0, 0, 0, 0);
        OrbitalState state = orbit.stateAt(MU, (Math.PI / 2 - e) / n);
        assertState(new OrbitalState(new Vec3(-a * e, a * Math.sqrt(1 - e * e), 0),
                new Vec3(-a * n, 0, 0)), state);
    }

    @Test
    public void conicsConserveSpecificEnergy() {
        for (double eccentricity : new double[] {0, 0.7, 0.999999, 1, 1.000001, 1.6}) {
            OrbitElements orbit = new OrbitElements(80_000 * (1 + eccentricity), eccentricity, 0.9, 1.5, -0.7, 0, 40);
            double expectedEnergy = -MU / (2 * orbit.semiMajorAxis());
            for (double elapsed : new double[] {-10_000, -250, 0, 60, 500, 9_000}) {
                OrbitalState state = orbit.stateAt(MU, orbit.epochSeconds() + elapsed);
                double energy = state.velocity().lengthSqr() / 2 - MU / state.position().length();
                assertEquals("e = " + eccentricity + ", elapsed = " + elapsed, expectedEnergy, energy, 1e-6);
            }
        }
    }

    @Test
    public void refittingStatePreservesCircularInclinedAndEscapeTrajectories() {
        List<OrbitElements> orbits = List.of(
                OrbitElements.fromSemiMajorAxis(70_000, 0, Math.PI, 0, 0, 2, 10),
                OrbitElements.fromSemiMajorAxis(160_000, 0.5, 0.72, 0.4, -0.2, 0.2, 10),
                new OrbitElements(165_000, 1, 1.1, 0.3, 0.6, -0.25, 10),
                OrbitElements.fromSemiMajorAxis(-180_000, 1.4, 0.2, -0.14, 0.8, -0.1, 10));
        for (OrbitElements orbit : orbits) {
            for (double epoch : new double[] {-400, 10, 1_100}) {
                OrbitElements fitted = OrbitElements.fromState(MU, orbit.stateAt(MU, epoch), epoch);
                assertState(orbit.stateAt(MU, epoch), fitted.stateAt(MU, epoch));
                assertState(orbit.stateAt(MU, epoch + 30), fitted.stateAt(MU, epoch + 30));
            }
        }
    }

    @Test
    public void inboundCrossingFindsTheDescendingBranchOfEachConic() {
        for (double e : new double[] {0.08, 0.999999, 1, 1.000001, 1.6}) {
            var orbit = new OrbitElements(64_000 * (1 + e), e, 0.7, -0.7, 1.2, 0, 80);
            double crossing = orbit.inwardCrossing(MU, 65_000, 0);
            var state = orbit.stateAt(MU, crossing);
            assertEquals(65_000, state.position().length(), 1e-5);
            assertTrue(state.position().dot(state.velocity()) < 0);
            double next = orbit.inwardCrossing(MU, 65_000, crossing + 0.001);
            assertEquals(crossing + orbit.period(MU), next, 1e-5);
            assertEquals(Double.POSITIVE_INFINITY, orbit.inwardCrossing(MU, orbit.periapsis(), 0), 0);
        }
    }

    @Test
    public void inboundCrossingDetectsAnExcursionShorterThanOneTick() {
        double radius = 65_000;
        var orbit = OrbitElements.fromSemiMajorAxis(66_000, 1 - (radius - 0.00001) / 66_000, 0, 0, 0, 0, 4.025);
        assertTrue(orbit.stateAt(MU, 4.0).position().length() > radius);
        assertTrue(orbit.stateAt(MU, 4.05).position().length() > radius);
        double crossing = orbit.inwardCrossing(MU, radius, 4);
        assertTrue(crossing > 4 && crossing < 4.025);
        assertEquals(Double.POSITIVE_INFINITY, orbit.inwardCrossing(MU, orbit.apoapsis(), 4), 0);
    }

    static void assertState(OrbitalState expected, OrbitalState actual) {
        assertEquals("position error (m)", 0, expected.position().distanceTo(actual.position()), 1e-5);
        assertEquals("velocity error (m/s)", 0, expected.velocity().distanceTo(actual.velocity()), 1e-8);
    }
}

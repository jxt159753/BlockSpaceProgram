package io.github.jxt159753.blockspaceprogram.orbit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.junit.Test;

public class PlanetFrameTest {
    private static final CelestialBody EARTH = OrbitalSystem.M3.body(OrbitalSystem.MOTHER_PLANET);
    private static final double SEA_LEVEL = 63;

    @Test
    public void surfaceVelocityUsesSpinAndUnscaledLocalComponents() {
        double width = PlanetFrame.worldWidth(EARTH);
        var frame = PlanetFrame.atDimension(EARTH, SEA_LEVEL, new Vec3(0, 6_063, -width / 6), 0);
        var state = frame.toOrbit(new Vec3(400, 80, -30));
        double up = Math.sqrt(3) / 2;
        double spin = 2 * Math.PI * 66_000 * 0.5 / 1_200;
        OrbitElementsTest.assertState(new OrbitalState(new Vec3(33_000, 0, 66_000 * up),
                new Vec3(40 - 30 * up, 400 + spin, 80 * up + 15)), state);
        var rotated = frame.dimensionToInertialRotation().transform(new Vector3d(0, 1, 0));
        assertEquals(0.5, rotated.x, 1e-12);
        assertEquals(up, rotated.z, 1e-12);
    }

    @Test
    public void roundTripWrapsLongitudeAtTheSeam() {
        double width = PlanetFrame.worldWidth(EARTH);
        var velocity = new Vec3(275, -150, -100);
        for (double z : new double[] {-width / 6, 0, width / 6}) {
            for (double x : new double[] {-width / 2 - 0.25, -width / 2 + 0.25, width / 2 + 0.25}) {
                var position = new Vec3(x, 7_013, z);
                var state = PlanetFrame.atDimension(EARTH, SEA_LEVEL, position, 563).toOrbit(velocity);
                var inverse = PlanetFrame.atOrbit(EARTH, SEA_LEVEL, state.position(), 563);
                assertEquals(0, Math.IEEEremainder(inverse.dimensionPosition().x - x, width), 1e-8);
                assertTrue(inverse.dimensionPosition().x >= -width / 2 && inverse.dimensionPosition().x < width / 2);
                assertEquals(position.y, inverse.dimensionPosition().y, 1e-8);
                assertEquals(z, inverse.dimensionPosition().z, 1e-8);
                assertEquals(0, velocity.distanceTo(inverse.toDimensionVelocity(state.velocity())), 1e-10);
            }
        }
    }

    @Test
    public void aPolarDescentSelectsAnInBandBlockOnTheCentralMeridian() {
        double width = PlanetFrame.worldWidth(EARTH);
        var northern = PlanetFrame.atOrbit(EARTH, SEA_LEVEL, new Vec3(0, 0, 65_000), 300);
        var southern = PlanetFrame.atOrbit(EARTH, SEA_LEVEL, new Vec3(0, 0, -65_000), 300);
        assertEquals(new Vec3(0, 5_063, -width / 4 + 0.5), northern.dimensionPosition());
        assertEquals(new Vec3(0, 5_063, width / 4 - 0.5), southern.dimensionPosition());
        assertTrue(PlanetFrame.isInProjection(EARTH, northern.dimensionPosition()));
        assertTrue(PlanetFrame.isInProjection(EARTH, southern.dimensionPosition()));
        var inbound = new OrbitalState(new Vec3(0, 0, 65_000), new Vec3(-720, 32, -90));
        var placed = PlanetFrame.atDimension(EARTH, SEA_LEVEL, northern.dimensionPosition(), 300)
                .toOrbit(northern.toDimensionVelocity(inbound.velocity()));
        assertTrue(placed.position().distanceTo(inbound.position()) < 1);
        double mu = EARTH.gravitationalParameter();
        assertEquals(inbound.specificEnergy(mu), placed.specificEnergy(mu), 1e-8);
    }
}

package io.github.jxt159753.blockspaceprogram.orbit;

import org.joml.Vector3d;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OrbitalGravityTest {
    private static final double MU = 3.528e10;
    private static final double RADIUS = 70_000;
    private static final Vector3d REFERENCE = new Vector3d(RADIUS, 0, 0);

    @Test
    public void referencePointIsInFreeFall() {
        assertEquals(0, OrbitalGravity.difference(MU, REFERENCE, new Vector3d()).length(), 0);
    }

    @Test
    public void radialGradientUsesBothExactRadii() {
        double outer = OrbitalGravity.difference(MU, REFERENCE, new Vector3d(1_000, 0, 0)).x;
        double inner = OrbitalGravity.difference(MU, REFERENCE, new Vector3d(-1_000, 0, 0)).x;
        assertEquals(MU / (RADIUS * RADIUS) - MU / (71_000.0 * 71_000), outer, 1e-12);
        assertEquals(MU / (RADIUS * RADIUS) - MU / (69_000.0 * 69_000), inner, 1e-12);
    }

    @Test
    public void alongTrackOffsetProducesRadialAndTangentialAcceleration() {
        double offset = 1_000;
        double radius = Math.hypot(RADIUS, offset);
        var correction = OrbitalGravity.difference(MU, REFERENCE, new Vector3d(0, offset, 0));
        assertEquals(MU / (RADIUS * RADIUS) - MU * RADIUS / (radius * radius * radius), correction.x, 1e-12);
        assertEquals(-MU * offset / (radius * radius * radius), correction.y, 1e-12);
        assertEquals(0, correction.z, 0);
    }
}

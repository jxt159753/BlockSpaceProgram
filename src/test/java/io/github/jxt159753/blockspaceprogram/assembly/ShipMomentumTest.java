package io.github.jxt159753.blockspaceprogram.assembly;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ShipMomentumTest {
    @Test
    public void addsOrbitalAndSpinMomentumInWorldAxes() {
        var center = new Vector3d(1, 0, 0);
        var a = ShipMomentum.of(3, new Matrix3d().scaling(2, 4, 5), new Quaterniond(), new Vector3d(),
                new Vector3d(0, 2, 0), new Vector3d(0, 0, 1), center);
        var b = ShipMomentum.of(1, new Matrix3d().scaling(1, 2, 3), new Quaterniond().rotationZ(Math.PI / 2),
                new Vector3d(4, 0, 0), new Vector3d(0, -2, 0), new Vector3d(1, 0, 0), center);
        var combined = a.add(b);

        assertEquals(new Vector3d(0, 1, 0), combined.velocity(4));
        assertEquals(2, combined.angular().x, 1e-12);
        assertEquals(-7, combined.angular().z, 1e-12);
        var spin = combined.spin(new Matrix3d().scaling(0.25, 0.5, 0.125), new Quaterniond().rotationZ(Math.PI / 2));
        assertEquals(1, spin.x, 1e-12);
        assertEquals(0, spin.y, 1e-12);
        assertEquals(-0.875, spin.z, 1e-12);
    }
}

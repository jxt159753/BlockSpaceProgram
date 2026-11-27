package io.github.jxt159753.blockspaceprogram.orbit;

import org.joml.Vector3d;
import org.joml.Vector3dc;

final class OrbitalGravity {
    private OrbitalGravity() {}

    static Vector3d difference(double mu, Vector3dc reference, Vector3dc offset) {
        return acceleration(mu, reference.add(offset, new Vector3d())).sub(acceleration(mu, reference));
    }

    private static Vector3d acceleration(double mu, Vector3dc position) {
        double radiusSquared = position.lengthSquared();
        return position.mul(-mu / (radiusSquared * Math.sqrt(radiusSquared)), new Vector3d());
    }
}

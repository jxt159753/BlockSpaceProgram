package io.github.jxt159753.blockspaceprogram.assembly;

import org.joml.Matrix3dc;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

record ShipMomentum(Vector3d linear, Vector3d angular) {
    /** World-space momentum about the same reference point for every contributing rigid body. */
    static ShipMomentum of(double mass, Matrix3dc inertia, Quaterniondc orientation, Vector3dc center,
            Vector3dc velocity, Vector3dc spin, Vector3dc reference) {
        var linear = velocity.mul(mass, new Vector3d());
        var angular = orientation.transform(inertia.transform(orientation.transformInverse(spin, new Vector3d())));
        angular.add(center.sub(reference, new Vector3d()).cross(linear));
        return new ShipMomentum(linear, angular);
    }

    ShipMomentum add(ShipMomentum other) {
        return new ShipMomentum(linear.add(other.linear, new Vector3d()), angular.add(other.angular, new Vector3d()));
    }

    Vector3d velocity(double mass) {
        return linear.div(mass, new Vector3d());
    }

    Vector3d spin(Matrix3dc inverseInertia, Quaterniondc orientation) {
        return orientation.transform(inverseInertia.transform(orientation.transformInverse(angular, new Vector3d())));
    }
}

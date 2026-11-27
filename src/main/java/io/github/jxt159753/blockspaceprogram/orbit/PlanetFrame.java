package io.github.jxt159753.blockspaceprogram.orbit;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3d;
import org.joml.Quaterniond;

/** The rotating surface frame at one position and time. Minecraft axes are east, up and south. */
public record PlanetFrame(Vec3 dimensionPosition, Vec3 relativePosition, Vec3 east, Vec3 up, Vec3 south,
                          Vec3 surfaceVelocity) {
    private static final double FULL_TURN = 2 * Math.PI;
    private static final double MOTHER_WORLD_WIDTH = 377_088;
    private static final double MOTHER_ROTATION_SECONDS = 1_200;

    public static double worldWidth(CelestialBody body) {
        return body.id().equals(OrbitalSystem.MOTHER_PLANET) ? MOTHER_WORLD_WIDTH
                : Math.round(FULL_TURN * body.radius() / 16) * 16.0;
    }

    public static boolean isInProjection(CelestialBody body, Vec3 position) {
        return Math.abs(position.z) < worldWidth(body) / 4;
    }

    public static PlanetFrame atDimension(CelestialBody body, double seaLevel, Vec3 position, double seconds) {
        double width = worldWidth(body);
        var spin = spin(body, seconds);
        double longitude = FULL_TURN * Math.IEEEremainder(position.x / width, 1) + spin.angle();
        double latitude = Mth.clamp(-FULL_TURN * position.z / width, -Math.PI / 2, Math.PI / 2);
        double radius = body.radius() + position.y - seaLevel;
        return at(position, radius, longitude, latitude, spin.rate());
    }

    public static PlanetFrame atOrbit(CelestialBody body, double seaLevel, Vec3 relativePosition, double seconds) {
        double width = worldWidth(body);
        double radius = relativePosition.length();
        double horizontal = Math.hypot(relativePosition.x, relativePosition.y);
        double latitude = Math.atan2(relativePosition.z, horizontal);
        var spin = spin(body, seconds);
        // The poles have no unique longitude. Use a block center just inside the central meridian.
        boolean atPole = horizontal <= 1e-12 * radius;
        double longitude = atPole ? 0 : wrap(Math.atan2(relativePosition.y, relativePosition.x) - spin.angle());
        double z = atPole ? Math.copySign(width / 4 - 0.5, -latitude) : -width * latitude / FULL_TURN;
        var position = new Vec3(width * longitude / FULL_TURN, seaLevel + (radius - body.radius()), z);
        return at(position, radius, longitude + spin.angle(), -FULL_TURN * z / width, spin.rate());
    }

    public static Vec3 primeMeridian(CelestialBody body, double seconds) {
        double angle = spin(body, seconds).angle();
        return new Vec3(Math.cos(angle), Math.sin(angle), 0);
    }

    public Vec3 toInertialDirection(Vec3 direction) {
        return east.scale(direction.x).add(up.scale(direction.y)).add(south.scale(direction.z));
    }

    public Vec3 toDimensionDirection(Vec3 direction) {
        return new Vec3(direction.dot(east), direction.dot(up), direction.dot(south));
    }

    public Quaterniond dimensionToInertialRotation() {
        return new Quaterniond().setFromNormalized(new Matrix3d()
                .setColumn(0, east.x, east.y, east.z)
                .setColumn(1, up.x, up.y, up.z)
                .setColumn(2, south.x, south.y, south.z)).normalize();
    }

    public OrbitalState toOrbit(Vec3 dimensionVelocity) {
        return new OrbitalState(relativePosition, toInertialDirection(dimensionVelocity).add(surfaceVelocity));
    }

    public Vec3 toDimensionVelocity(Vec3 orbitalVelocity) {
        return toDimensionDirection(orbitalVelocity.subtract(surfaceVelocity));
    }

    private static PlanetFrame at(Vec3 dimensionPosition, double radius, double longitude, double latitude, double spinRate) {
        double cosLatitude = Math.cos(latitude);
        double sinLongitude = Math.sin(longitude);
        double cosLongitude = Math.cos(longitude);
        var east = new Vec3(-sinLongitude, cosLongitude, 0);
        var up = new Vec3(cosLongitude * cosLatitude, sinLongitude * cosLatitude, Math.sin(latitude));
        return new PlanetFrame(dimensionPosition, up.scale(radius), east, up, east.cross(up),
                east.scale(radius * cosLatitude * spinRate));
    }

    private static double wrap(double angle) {
        double wrapped = Math.IEEEremainder(angle, FULL_TURN);
        return wrapped >= Math.PI ? wrapped - FULL_TURN : wrapped;
    }

    private static Spin spin(CelestialBody body, double seconds) {
        if (body.orbit() == null) {
            return new Spin(FULL_TURN * (seconds % MOTHER_ROTATION_SECONDS) / MOTHER_ROTATION_SECONDS,
                    FULL_TURN / MOTHER_ROTATION_SECONDS);
        }
        // The M3 satellite keeps its prime meridian facing the parent.
        var state = OrbitalSystem.M3.relativeState(body.orbit(), seconds);
        Vec3 r = state.position();
        Vec3 v = state.velocity();
        double rate = (r.x * v.y - r.y * v.x) / (r.x * r.x + r.y * r.y);
        return new Spin(Math.atan2(-r.y, -r.x), rate);
    }

    private record Spin(double angle, double rate) {}
}

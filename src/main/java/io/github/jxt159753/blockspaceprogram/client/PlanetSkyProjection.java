package io.github.jxt159753.blockspaceprogram.client;

import io.github.jxt159753.blockspaceprogram.assembly.PlanetFlightProperties;
import io.github.jxt159753.blockspaceprogram.orbit.CelestialBody;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSystem;
import io.github.jxt159753.blockspaceprogram.sky.SkyNetworking.OrbitalView;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

final class PlanetSkyProjection {
    static final Vec3 NORTH_POLE = new Vec3(0, 0, 1);
    // M3 describes a planet and its satellite. Sunlight is parallel in this local system.
    static final Vec3 SUN_DIRECTION = new Vec3(0, 1, 0);
    private static final double FULL_TURN = 2 * Math.PI;
    private static final double MOTHER_WORLD_WIDTH = 377_088;
    private static final double ROTATION_SECONDS = 1_200;

    private PlanetSkyProjection() {}

    @Nullable
    static CelestialBody bodyForDimension(ResourceLocation dimension) {
        for (var body : OrbitalSystem.M3.bodies()) {
            if (body.id().equals(dimension)) {
                return body;
            }
        }
        return null;
    }

    static Observer onSurface(CelestialBody body, PlanetFlightProperties properties, Vec3 camera, double seconds) {
        // Issue #4: x east, z south, with the equator and prime meridian at x=z=0.
        double width = body.id().equals(OrbitalSystem.MOTHER_PLANET) ? MOTHER_WORLD_WIDTH
                : Math.round(FULL_TURN * body.radius() / 16) * 16.0;
        double longitude = FULL_TURN * Math.IEEEremainder(camera.x / width, 1) + rotation(body, seconds);
        double latitude = Mth.clamp(-FULL_TURN * camera.z / width, -Math.PI / 2, Math.PI / 2);
        Vec3 east = new Vec3(-Math.sin(longitude), Math.cos(longitude), 0);
        Vec3 up = new Vec3(Math.cos(longitude) * Math.cos(latitude), Math.sin(longitude) * Math.cos(latitude),
                Math.sin(latitude));
        Vec3 center = OrbitalSystem.M3.bodyState(body.id(), seconds).position();
        double distance = body.radius() + Math.max(0, camera.y - properties.seaLevel());
        return new Observer(center.add(up.scale(distance)), east, up, east.cross(up));
    }

    static Observer inOrbit(OrbitalView view, Vec3 camera, double seconds) {
        Vec3 offset = camera.subtract(view.regionOrigin());
        Vec3 reference = OrbitalSystem.M3.absoluteState(view.reference().orElseThrow(), seconds).position();
        // Minecraft's Y-up region coordinates become Z-up in the inertial frame.
        return new Observer(reference.add(offset.x, -offset.z, offset.y), new Vec3(1, 0, 0), NORTH_POLE,
                new Vec3(0, -1, 0));
    }

    static Vec3 primeMeridian(CelestialBody body, double seconds) {
        double angle = rotation(body, seconds);
        return new Vec3(Math.cos(angle), Math.sin(angle), 0);
    }

    private static double rotation(CelestialBody body, double seconds) {
        if (body.orbit() != null) {
            Vec3 relative = OrbitalSystem.M3.relativeState(body.orbit(), seconds).position();
            return Math.atan2(-relative.y, -relative.x);
        }
        return FULL_TURN * (seconds % ROTATION_SECONDS) / ROTATION_SECONDS;
    }

    record Observer(Vec3 position, Vec3 east, Vec3 up, Vec3 south) {
        Vec3 toLocal(Vec3 vector) {
            return new Vec3(vector.dot(east), vector.dot(up), vector.dot(south));
        }

        Matrix4f starsToLocal() {
            return new Matrix4f(
                    (float) east.x, (float) up.x, (float) south.x, 0,
                    (float) east.y, (float) up.y, (float) south.y, 0,
                    (float) east.z, (float) up.z, (float) south.z, 0,
                    0, 0, 0, 1);
        }
    }
}

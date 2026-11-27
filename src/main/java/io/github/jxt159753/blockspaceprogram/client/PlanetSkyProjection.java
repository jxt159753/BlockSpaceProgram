package io.github.jxt159753.blockspaceprogram.client;

import io.github.jxt159753.blockspaceprogram.assembly.PlanetFlightProperties;
import io.github.jxt159753.blockspaceprogram.orbit.CelestialBody;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalRegion;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSystem;
import io.github.jxt159753.blockspaceprogram.orbit.PlanetFrame;
import io.github.jxt159753.blockspaceprogram.sky.SkyNetworking.OrbitalView;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

final class PlanetSkyProjection {
    static final Vec3 NORTH_POLE = new Vec3(0, 0, 1);
    // M3 describes a planet and its satellite. Sunlight is parallel in this local system.
    static final Vec3 SUN_DIRECTION = new Vec3(0, 1, 0);
    private static final int DAY_TICKS = 24_000;

    private PlanetSkyProjection() {}

    static float solarAngle(Vec3 sun) {
        float angle = (float) (Math.acos(Mth.clamp(sun.y, -1, 1)) / (2 * Math.PI));
        return sun.x > 0 ? 1 - angle : angle;
    }

    static long vanillaDayTime(long dayTime, Vec3 sun) {
        double angle = solarAngle(sun);
        double phase = angle;
        // Invert DimensionType.timeOfDay for packs that rederive the solar angle from worldTime.
        for (int i = 0; i < 4; i++) {
            double radians = phase * Math.PI;
            double eased = (2 * phase + (1 - Math.cos(radians)) / 2) / 3;
            double slope = (2 + Math.PI / 2 * Math.sin(radians)) / 3;
            phase -= (eased - angle) / slope;
        }
        long ticks = Math.round((phase + 0.25) * DAY_TICKS) % DAY_TICKS;
        return dayTime - Math.floorMod(dayTime, DAY_TICKS) + ticks;
    }

    static Observer onSurface(OrbitalSystem system, CelestialBody body, PlanetFlightProperties properties, Vec3 camera,
                              double seconds) {
        var frame = PlanetFrame.atDimension(system, body, properties.seaLevel(),
                new Vec3(camera.x, Math.max(camera.y, properties.seaLevel()), camera.z), seconds);
        Vec3 center = system.bodyState(body.id(), seconds).position();
        return new Observer(center.add(frame.relativePosition()), frame.east(), frame.up(), frame.south());
    }

    static Observer inOrbit(OrbitalSystem system, OrbitalView view, Vec3 camera, double seconds) {
        Vec3 offset = camera.subtract(view.regionOrigin());
        Vec3 reference = system.absoluteState(view.reference().orElseThrow(), seconds).position();
        // Minecraft's Y-up region coordinates become Z-up in the inertial frame.
        return new Observer(reference.add(OrbitalRegion.toInertial(offset)), new Vec3(1, 0, 0), NORTH_POLE,
                new Vec3(0, -1, 0));
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

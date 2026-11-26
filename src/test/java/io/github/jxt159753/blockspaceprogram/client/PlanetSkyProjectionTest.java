package io.github.jxt159753.blockspaceprogram.client;

import static org.junit.Assert.assertEquals;

import io.github.jxt159753.blockspaceprogram.assembly.PlanetFlightProperties;
import io.github.jxt159753.blockspaceprogram.orbit.Orbit;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitElements;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSystem;
import io.github.jxt159753.blockspaceprogram.sky.SkyNetworking.OrbitalView;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

public class PlanetSkyProjectionTest {
    private static final PlanetFlightProperties OVERWORLD = new PlanetFlightProperties(63, 60_000, 9.8, 1.225, 4_000, 1_200);
    private static final ResourceLocation EARTH = OrbitalSystem.MOTHER_PLANET;
    private static final Vec3 EQUATOR = new Vec3(0, 63, 0);

    @Test
    public void solarElevationUsesOrbitalTimeAndLongitude() {
        assertVector(new Vec3(1, 0, 0), localSun(EQUATOR, 0));
        assertVector(new Vec3(0, 1, 0), localSun(EQUATOR, 300));
        assertVector(new Vec3(-1, 0, 0), localSun(EQUATOR, 600));
        assertVector(new Vec3(0, -1, 0), localSun(EQUATOR, 900));
        assertVector(localSun(EQUATOR, 300), localSun(EQUATOR.add(377_088, 0, 0), 300));
        assertVector(localSun(EQUATOR, 300), localSun(EQUATOR.add(94_272, 0, 0), 0));
    }

    @Test
    public void surfaceViewAccountsForParallaxAndAngularSize() {
        Vec3 moon = OrbitalSystem.M3.bodyState(OrbitalSystem.MOON, 0).position();
        var observer = surface(EQUATOR, 0);
        assertVector(new Vec3(0, 300_000, 0), observer.toLocal(moon.subtract(observer.position())));
        assertEquals(0.04, 12_000 / moon.distanceTo(observer.position()), 1e-10);

        var high = surface(EQUATOR.add(0, 10_000, 0), 0);
        assertVector(new Vec3(0, -70_000, 0), high.toLocal(high.position().scale(-1)));
        assertEquals(0.8571428571428571, 60_000 / high.position().length(), 1e-10);
    }

    @Test
    public void orbitalCameraFollowsReferenceAndIncludesLocalOffset() {
        var reference = new Orbit(EARTH, OrbitElements.fromSemiMajorAxis(70_000, 0, 0, 0, 0, 0, 0));
        var origin = new Vec3(1_000, 500, -2_000);
        var region = new OrbitalView(ResourceLocation.withDefaultNamespace("the_end"), origin, Optional.of(reference));
        Vec3 camera = origin.add(10, 20, 30);
        double quarterPeriod = Math.PI / 2 * Math.sqrt(Math.pow(70_000, 3) / 3.528e10);
        assertVector(new Vec3(70_010, -30, 20), PlanetSkyProjection.inOrbit(region, camera, 0).position());
        assertVector(new Vec3(10, 69_970, 20), PlanetSkyProjection.inOrbit(region, camera, quarterPeriod).position());
    }

    private static PlanetSkyProjection.Observer surface(Vec3 camera, double seconds) {
        return PlanetSkyProjection.onSurface(OrbitalSystem.M3.body(EARTH), OVERWORLD, camera, seconds);
    }

    private static Vec3 localSun(Vec3 camera, double seconds) {
        return surface(camera, seconds).toLocal(PlanetSkyProjection.SUN_DIRECTION);
    }

    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 1e-7);
        assertEquals(expected.y, actual.y, 1e-7);
        assertEquals(expected.z, actual.z, 1e-7);
    }
}

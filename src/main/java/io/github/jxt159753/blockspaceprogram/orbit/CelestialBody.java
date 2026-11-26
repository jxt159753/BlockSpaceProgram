package io.github.jxt159753.blockspaceprogram.orbit;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Radius in metres, gravitational parameter in m³/s², and an orbit around the parent if present. */
public record CelestialBody(ResourceLocation id, double radius, double gravitationalParameter, @Nullable Orbit orbit) {
    public CelestialBody {
        if (!(radius > 0) || !Double.isFinite(radius)
                || !(gravitationalParameter > 0) || !Double.isFinite(gravitationalParameter)) {
            throw new IllegalArgumentException("Invalid celestial body radius or gravitational parameter: " + id);
        }
    }
}

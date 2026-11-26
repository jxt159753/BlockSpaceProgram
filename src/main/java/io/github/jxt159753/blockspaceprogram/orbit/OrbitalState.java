package io.github.jxt159753.blockspaceprogram.orbit;

import net.minecraft.world.phys.Vec3;

/** Position in metres and velocity in metres per second in the same inertial frame. */
public record OrbitalState(Vec3 position, Vec3 velocity) {
    public OrbitalState add(OrbitalState other) {
        return new OrbitalState(position.add(other.position), velocity.add(other.velocity));
    }

    public OrbitalState subtract(OrbitalState other) {
        return new OrbitalState(position.subtract(other.position), velocity.subtract(other.velocity));
    }
}

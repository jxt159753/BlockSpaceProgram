package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

/**
 * On-rails ship and the state needed to hand it back to local physics.
 * Angular velocity is in inertial axes, in radians per second.
 */
public record OrbitingShip(UUID id, Orbit orbit, Quaterniond orientation, Vec3 angularVelocity, CompoundTag shipData) {
    private static final Codec<Quaterniond> ORIENTATION_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.DOUBLE.fieldOf("x").forGetter(Quaterniond::x),
            Codec.DOUBLE.fieldOf("y").forGetter(Quaterniond::y),
            Codec.DOUBLE.fieldOf("z").forGetter(Quaterniond::z),
            Codec.DOUBLE.fieldOf("w").forGetter(Quaterniond::w)
    ).apply(instance, Quaterniond::new));
    public static final Codec<OrbitingShip> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(OrbitingShip::id),
            Orbit.CODEC.fieldOf("orbit").forGetter(OrbitingShip::orbit),
            ORIENTATION_CODEC.fieldOf("orientation").forGetter(OrbitingShip::orientation),
            Vec3.CODEC.fieldOf("angular_velocity").forGetter(OrbitingShip::angularVelocity),
            CompoundTag.CODEC.fieldOf("ship_data").forGetter(OrbitingShip::shipData)
    ).apply(instance, OrbitingShip::new));

    public OrbitingShip {
        if (!orientation.isFinite() || !(orientation.lengthSquared() > 0)
                || !Double.isFinite(angularVelocity.lengthSqr())) {
            throw new IllegalArgumentException("Invalid on-rails ship rotation");
        }
        orientation = new Quaterniond(orientation);
        shipData = shipData.copy();
    }

    /** Ship-local to inertial rotation retained while on rails. */
    @Override
    public Quaterniond orientation() {
        return new Quaterniond(orientation);
    }

    @Override
    public CompoundTag shipData() {
        return shipData.copy();
    }

    OrbitingShip withOrbit(Orbit next) {
        return new OrbitingShip(id, next, orientation, angularVelocity, shipData);
    }
}

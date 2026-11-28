package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;

/**
 * On-rails ship and the state needed to hand it back to local physics.
 * Angular velocity is in inertial axes, in radians per second.
 */
public record OrbitingShip(UUID id, Orbit orbit, Quaterniond orientation, Vec3 angularVelocity, CompoundTag shipData,
                           Optional<ManeuverNode> maneuver) {
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
            CompoundTag.CODEC.fieldOf("ship_data").forGetter(OrbitingShip::shipData),
            ManeuverNode.CODEC.optionalFieldOf("maneuver").forGetter(OrbitingShip::maneuver)
    ).apply(instance, OrbitingShip::new));

    public OrbitingShip(UUID id, Orbit orbit, Quaterniond orientation, Vec3 angularVelocity, CompoundTag shipData) {
        this(id, orbit, orientation, angularVelocity, shipData, Optional.empty());
    }

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

    @Nullable
    public UUID ownerId() {
        String key = AssembledShipEntity.SAVED_OWNER_TAG;
        return shipData.hasUUID(key) ? shipData.getUUID(key) : null;
    }

    @Nullable
    public String customNameJson() {
        return shipData.contains("CustomName") ? shipData.getString("CustomName") : null;
    }

    public OrbitingShip withOrbit(Orbit next) {
        return new OrbitingShip(id, next, orientation, angularVelocity, shipData, maneuver);
    }

    OrbitingShip withManeuver(Optional<ManeuverNode> next) {
        return new OrbitingShip(id, orbit, orientation, angularVelocity, shipData, next);
    }
}

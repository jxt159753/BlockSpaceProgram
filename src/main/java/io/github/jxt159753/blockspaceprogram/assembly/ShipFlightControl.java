package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

/** Near-surface flight telemetry and setpoints. Use on the server thread while the ship is loaded. */
public interface ShipFlightControl {
    Telemetry telemetry();

    void setThrottle(ThrottleMode mode, double target);

    void setAxes(double pitch, double yaw, double roll);

    void setRcs(boolean enabled);

    void setTranslation(double x, double y, double z);

    void setAttitudeMode(AttitudeMode mode);

    void holdAttitude(double pitch, double yaw, double roll);

    boolean requestStage();

    /** Keeps one remote controller and its ship loaded while the supplied connection is live. */
    RemoteResult claimRemote(BooleanSupplier connection);

    boolean releaseRemote(BooleanSupplier connection);

    enum AttitudeMode {
        MANUAL, KILL_ROTATION, HOLD, PROGRADE, RETROGRADE;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum ThrottleMode {
        MANUAL, DIRECT, VERTICAL_SPEED, THRUST_TO_WEIGHT;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum RemoteResult {
        CONNECTED, BUSY, TICKET_LIMIT
    }

    record Attitude(double pitch, double yaw, double roll) {
        public static Attitude fromOrientation(Quaterniondc orientation) {
            var angles = orientation.getEulerAnglesYXZ(new Vector3d());
            return new Attitude(Math.toDegrees(angles.x), Math.toDegrees(angles.y), Math.toDegrees(angles.z));
        }
    }

    record Propellant(double fuel, double electric) {}

    record Telemetry(UUID shipId, ResourceLocation dimension, Vec3 position, Vec3 velocity, double altitude,
                     Attitude attitude, Vec3 angularVelocity, double mass, double fuel, double electric, double thrust,
                     double availableThrust, double gravity, double thrustToWeight, double throttle, int stage,
                     AttitudeMode attitudeMode, ThrottleMode throttleMode, Vec3 axes, boolean landed,
                     @Nullable PlanetFlightProperties planet, double verticalSpeed, boolean rcs, Vec3 translation) {
        public Telemetry(UUID shipId, ResourceLocation dimension, Vec3 position, Vec3 velocity, double altitude,
                         Attitude attitude, Vec3 angularVelocity, double mass, double fuel, double electric, double thrust,
                         double availableThrust, double gravity, double thrustToWeight, double throttle, int stage,
                         AttitudeMode attitudeMode, ThrottleMode throttleMode, Vec3 axes, boolean landed,
                         @Nullable PlanetFlightProperties planet, double verticalSpeed) {
            this(shipId, dimension, position, velocity, altitude, attitude, angularVelocity, mass, fuel, electric, thrust,
                    availableThrust, gravity, thrustToWeight, throttle, stage, attitudeMode, throttleMode, axes,
                    landed, planet, verticalSpeed, false, Vec3.ZERO);
        }

        public Telemetry(UUID shipId, ResourceLocation dimension, Vec3 position, Vec3 velocity, double altitude,
                         Attitude attitude, Vec3 angularVelocity, double mass, double fuel, double electric, double thrust,
                         double availableThrust, double gravity, double thrustToWeight, double throttle, int stage,
                         AttitudeMode attitudeMode, ThrottleMode throttleMode, Vec3 axes, boolean landed,
                         @Nullable PlanetFlightProperties planet) {
            this(shipId, dimension, position, velocity, altitude, attitude, angularVelocity, mass, fuel, electric, thrust,
                    availableThrust, gravity, thrustToWeight, throttle, stage, attitudeMode, throttleMode, axes,
                    landed, planet, velocity.y);
        }
    }
}

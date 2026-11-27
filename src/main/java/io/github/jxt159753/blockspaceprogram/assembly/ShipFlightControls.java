package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.function.BooleanSupplier;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import static io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.*;
import static io.github.jxt159753.blockspaceprogram.assembly.ShipFlightInput.*;

/** Arbitrates the pilot and automation and resolves their setpoints into normalized actuator inputs. */
final class ShipFlightControls {
    private static final double THROTTLE_STEP = 0.05;
    static final double WHEEL_ACCELERATION = 1.5;
    private static final double ATTITUDE_GAIN = 6;
    private static final double ANGULAR_DAMPING = 4.5;
    private static final double VERTICAL_SPEED_GAIN = 1.5;
    private static final double MIN_TRACKING_SPEED_SQUARED = 0.01;
    private static final double ANGULAR_DEADBAND_SQUARED = 1e-6;

    private final Vector3d axes = new Vector3d();
    private final Vector3d directAxes = new Vector3d();
    private final Quaterniond heading = new Quaterniond();
    private AttitudeMode attitudeMode = AttitudeMode.MANUAL;
    private ThrottleMode throttleMode = ThrottleMode.MANUAL;
    private double throttleTarget;
    private double pilotThrottle;
    private double throttle;
    @Nullable
    private BooleanSupplier remote;

    AttitudeMode attitudeMode() {
        return attitudeMode;
    }

    ThrottleMode throttleMode() {
        return throttleMode;
    }

    double throttle() {
        return throttle;
    }

    Vector3dc axes() {
        return axes;
    }

    @Nullable
    BooleanSupplier remote() {
        return remote;
    }

    void connect(BooleanSupplier connection) {
        remote = connection;
    }

    boolean checkConnection(Quaterniondc orientation) {
        if (remote == null || remote.getAsBoolean()) {
            return false;
        }
        disconnect(orientation);
        return true;
    }

    void disconnect(Quaterniondc orientation) {
        remote = null;
        cutThrottle();
        setAttitudeMode(AttitudeMode.HOLD, orientation);
    }

    void inheritForHandoff(ShipFlightControls source, Quaterniondc rotation, Quaterniondc orientation) {
        if (source.remote != null) {
            // Flight terminals can only control ships in their own dimension.
            disconnect(orientation);
            return;
        }
        attitudeMode = source.attitudeMode;
        throttleMode = source.throttleMode;
        throttleTarget = source.throttleTarget;
        pilotThrottle = source.pilotThrottle;
        throttle = source.throttle;
        directAxes.set(source.directAxes);
        heading.set(rotation).mul(source.heading);
    }

    void tickPilot(int keys) {
        if ((keys & CUTOFF) != 0) {
            cutThrottle();
        } else if ((keys & (THROTTLE_UP | THROTTLE_DOWN)) != 0) {
            double previous = throttleMode == ThrottleMode.MANUAL ? pilotThrottle : throttle;
            pilotThrottle = Math.clamp(previous + THROTTLE_STEP * axis(keys, THROTTLE_UP, THROTTLE_DOWN), 0, 1);
        }
    }

    void clearPilot() {
        pilotThrottle = 0;
        if (throttleMode == ThrottleMode.MANUAL) {
            throttle = 0;
        }
    }

    private void cutThrottle() {
        throttleMode = ThrottleMode.MANUAL;
        throttleTarget = 0;
        pilotThrottle = 0;
        throttle = 0;
    }

    void setThrottle(ThrottleMode mode, double target) {
        if (!Double.isFinite(target) || (mode == ThrottleMode.DIRECT && (target < 0 || target > 1))
                || (mode == ThrottleMode.THRUST_TO_WEIGHT && target < 0)) {
            throw new IllegalArgumentException("Invalid throttle target");
        }
        if (mode == ThrottleMode.MANUAL) {
            pilotThrottle = throttle;
        }
        throttleMode = mode;
        throttleTarget = target;
    }

    boolean needsThrustEstimate() {
        return throttleMode == ThrottleMode.VERTICAL_SPEED || throttleMode == ThrottleMode.THRUST_TO_WEIGHT;
    }

    double resolveThrottle(int keys, double verticalSpeed, double accelerationToHover,
            double maxUpAcceleration, double maxThrustToWeight) {
        if ((keys & CUTOFF) != 0) {
            cutThrottle();
        } else if ((keys & (THROTTLE_UP | THROTTLE_DOWN)) != 0) {
            throttle = pilotThrottle;
        } else {
            throttle = switch (throttleMode) {
                case MANUAL -> pilotThrottle;
                case DIRECT -> throttleTarget;
                case VERTICAL_SPEED -> maxUpAcceleration > 0
                        ? Math.clamp((accelerationToHover + VERTICAL_SPEED_GAIN * (throttleTarget - verticalSpeed))
                                / maxUpAcceleration, 0, 1) : 0;
                case THRUST_TO_WEIGHT -> maxThrustToWeight > 0 ? Math.clamp(throttleTarget / maxThrustToWeight, 0, 1) : 0;
            };
        }
        return throttle;
    }

    void setAxes(double pitch, double yaw, double roll) {
        if (!isAxis(pitch) || !isAxis(yaw) || !isAxis(roll)) {
            throw new IllegalArgumentException("Attitude axes must be between -1 and 1");
        }
        attitudeMode = AttitudeMode.MANUAL;
        directAxes.set(pitch, yaw, roll);
    }

    private static boolean isAxis(double value) {
        return value >= -1 && value <= 1;
    }

    void setAttitudeMode(AttitudeMode mode, Quaterniondc orientation) {
        attitudeMode = mode;
        directAxes.zero();
        heading.set(orientation);
    }

    static Quaterniond heading(double pitch, double yaw, double roll) {
        if (!Double.isFinite(pitch) || !Double.isFinite(yaw) || !Double.isFinite(roll)) {
            throw new IllegalArgumentException("Attitude angles must be finite");
        }
        return new Quaterniond().rotationYXZ(Math.toRadians(yaw), Math.toRadians(pitch), Math.toRadians(roll));
    }

    Vector3dc resolveAxes(int keys, Quaterniondc orientation, Vector3dc velocity, Vector3dc angularVelocity) {
        if (attitudeMode == AttitudeMode.MANUAL) {
            axes.set(directAxes);
        } else {
            if ((attitudeMode == AttitudeMode.PROGRADE || attitudeMode == AttitudeMode.RETROGRADE)
                    && velocity.lengthSquared() >= MIN_TRACKING_SPEED_SQUARED) {
                var nose = orientation.transformPositiveY(new Vector3d());
                var direction = velocity.normalize(attitudeMode == AttitudeMode.PROGRADE ? 1 : -1, new Vector3d());
                heading.rotationTo(nose, direction).mul(orientation);
            }
            var rate = orientation.transformInverse(angularVelocity, new Vector3d());
            axes.zero();
            if (attitudeMode != AttitudeMode.KILL_ROTATION) {
                var error = orientation.conjugate(new Quaterniond()).mul(heading);
                axes.set(error.x(), error.y(), error.z());
                double sine = axes.length();
                if (sine > 0) {
                    double angle = 2 * Math.atan2(sine, Math.abs(error.w()));
                    axes.mul(Math.copySign(angle / sine, error.w()));
                }
            }
            if (axes.lengthSquared() < ANGULAR_DEADBAND_SQUARED && rate.lengthSquared() < ANGULAR_DEADBAND_SQUARED) {
                axes.zero();
            } else {
                axes.mul(ATTITUDE_GAIN).fma(-ANGULAR_DAMPING, rate).div(WHEEL_ACCELERATION);
                axes.set(Math.clamp(axes.x, -1, 1), Math.clamp(axes.y, -1, 1), Math.clamp(axes.z, -1, 1));
            }
        }
        if ((keys & (PITCH_DOWN | PITCH_UP)) != 0) {
            axes.x = axis(keys, PITCH_DOWN, PITCH_UP);
        }
        if ((keys & (YAW_LEFT | YAW_RIGHT)) != 0) {
            axes.y = axis(keys, YAW_LEFT, YAW_RIGHT);
        }
        if ((keys & (ROLL_RIGHT | ROLL_LEFT)) != 0) {
            axes.z = axis(keys, ROLL_RIGHT, ROLL_LEFT);
        }
        return axes;
    }
}

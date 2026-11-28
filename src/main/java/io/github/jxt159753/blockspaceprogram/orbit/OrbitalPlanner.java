package io.github.jxt159753.blockspaceprogram.orbit;

import java.util.function.DoubleFunction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class OrbitalPlanner {
    public static final double PREDICTION_SECONDS = 3600;
    public static final double NODE_LEAD_SECONDS = 3;
    private static final double TAU = 2 * Math.PI;
    private static final int APPROACH_STEPS = 720;

    private OrbitalPlanner() {}

    /** The classical window for same-direction, coplanar circular orbits around a common primary. */
    @Nullable
    public static TransferWindow hohmann(OrbitalSystem system, Orbit from, Orbit target, double now) {
        if (!from.center().equals(target.center()) || from.elements().eccentricity() > 1e-6
                || target.elements().eccentricity() > 1e-6) {
            return null;
        }
        double r1 = from.elements().semiMajorAxis();
        double r2 = target.elements().semiMajorAxis();
        if (Math.abs(r1 - r2) < 1) {
            return null;
        }
        var departure = system.relativeState(from, now);
        var destination = system.relativeState(target, now);
        Vec3 normal = departure.position().cross(departure.velocity()).normalize();
        Vec3 targetNormal = destination.position().cross(destination.velocity()).normalize();
        if (normal.dot(targetNormal) < Math.cos(1e-3)) {
            return null;
        }

        double mu = system.body(from.center()).gravitationalParameter();
        double n1 = TAU / from.elements().period(mu);
        double n2 = TAU / target.elements().period(mu);
        double transferAxis = (r1 + r2) / 2;
        double flightTime = hohmannTransferSeconds(transferAxis, mu);
        double phase = Math.atan2(normal.dot(departure.position().cross(destination.position())),
                departure.position().dot(destination.position()));
        double required = Math.IEEEremainder(Math.PI - n2 * flightTime, TAU);
        double relativeMotion = n2 - n1;
        double phaseAtLead = phase + relativeMotion * NODE_LEAD_SECONDS;
        double remaining = (required - phaseAtLead) * Math.signum(relativeMotion);
        double delay = (remaining - Math.floor(remaining / TAU) * TAU) / Math.abs(relativeMotion);
        double prograde = Math.sqrt(mu / r1) * (Math.sqrt(r2 / transferAxis) - 1);
        return new TransferWindow(phase, required, new ManeuverNode(now + NODE_LEAD_SECONDS + delay,
                new Vec3(prograde, 0, 0)));
    }

    static double hohmannTransferSeconds(double transferAxis, double mu) {
        return Math.PI * transferAxis / Math.sqrt(mu / transferAxis);
    }

    /** Search a finite forecast interval and refine each sampled local minimum. */
    public static Approach closestApproach(DoubleFunction<OrbitalState> ship, DoubleFunction<OrbitalState> target,
                                           double from, double to) {
        return closestApproach(ship, target, from, to, APPROACH_STEPS);
    }

    static Approach closestApproach(DoubleFunction<OrbitalState> ship, DoubleFunction<OrbitalState> target,
                                    double from, double to, int steps) {
        double step = (to - from) / steps;
        var best = approach(ship, target, from);
        double before = best.distanceSquared();
        double middle = separation(ship, target, from + step);
        best = closer(best, approach(ship, target, refineMinimum(ship, target, from, from + step)));
        for (int i = 2; i <= steps; i++) {
            double time = from + step * i;
            double after = separation(ship, target, time);
            if (middle <= before && middle <= after && (middle < before || middle < after)) {
                double refined = refineMinimum(ship, target, time - 2 * step, time);
                best = closer(best, approach(ship, target, refined));
            }
            before = middle;
            middle = after;
        }
        best = closer(best, approach(ship, target, refineMinimum(ship, target, to - step, to)));
        return closer(best, approach(ship, target, to));
    }

    private static Approach closer(Approach first, Approach second) {
        return second.distanceSquared() < first.distanceSquared() ? second : first;
    }

    /** Resolve the first surface crossing when closestApproach predicts a path inside the target body. */
    public static Approach surfaceContact(DoubleFunction<OrbitalState> ship, DoubleFunction<OrbitalState> target,
                                          double from, Approach closest, double radius) {
        double radiusSquared = radius * radius;
        if (separation(ship, target, from) <= radiusSquared) {
            return approach(ship, target, from);
        }
        double lo = from;
        double hi = closest.seconds();
        double step = (hi - lo) / APPROACH_STEPS;
        for (int i = 1; i < APPROACH_STEPS; i++) {
            double seconds = from + i * step;
            if (separation(ship, target, seconds) <= radiusSquared) {
                hi = seconds;
                break;
            }
            lo = seconds;
        }
        for (int i = 0; i < 24; i++) {
            double middle = (lo + hi) / 2;
            if (separation(ship, target, middle) > radiusSquared) {
                lo = middle;
            } else {
                hi = middle;
            }
        }
        return approach(ship, target, hi);
    }

    private static double refineMinimum(DoubleFunction<OrbitalState> ship, DoubleFunction<OrbitalState> target,
                                        double lo, double hi) {
        for (int i = 0; i < 20; i++) {
            double third = (hi - lo) / 3;
            if (separation(ship, target, lo + third) < separation(ship, target, hi - third)) {
                hi -= third;
            } else {
                lo += third;
            }
        }
        return (lo + hi) / 2;
    }

    private static double separation(DoubleFunction<OrbitalState> ship, DoubleFunction<OrbitalState> target,
                                     double seconds) {
        return ship.apply(seconds).position().distanceToSqr(target.apply(seconds).position());
    }

    private static Approach approach(DoubleFunction<OrbitalState> ship, DoubleFunction<OrbitalState> target,
                                     double seconds) {
        var a = ship.apply(seconds);
        var b = target.apply(seconds);
        return new Approach(seconds, a.position(), b.position(), a.velocity().distanceTo(b.velocity()));
    }

    public record TransferWindow(double currentPhase, double departurePhase, ManeuverNode node) {}

    public record Approach(double seconds, Vec3 shipPosition, Vec3 targetPosition, double relativeSpeed) {
        public double distanceSquared() {
            return shipPosition.distanceToSqr(targetPosition);
        }

        public double distance() {
            return Math.sqrt(distanceSquared());
        }
    }
}

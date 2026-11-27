package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.phys.Vec3;

/**
 * A Keplerian conic in a right-handed frame with an XY reference plane and +Z north.
 * Angles are in radians and the epoch uses the shared orbit clock in seconds.
 * The semi-latus rectum gives a finite size even for a parabola.
 */
public record OrbitElements(double semiLatusRectum, double eccentricity, double inclination,
                            double longitudeOfAscendingNode, double argumentOfPeriapsis,
                            double meanAnomalyAtEpoch, double epochSeconds) {
    private static final double TWO_PI = 2 * Math.PI;
    private static final double ANGLE_TOLERANCE = 1e-13;
    private static final double DEGENERATE_TOLERANCE = 1e-14;
    private static final Codec<Double> FINITE = Codec.doubleRange(-Double.MAX_VALUE, Double.MAX_VALUE);
    public static final Codec<OrbitElements> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.doubleRange(Double.MIN_VALUE, Double.MAX_VALUE).fieldOf("semi_latus_rectum").forGetter(OrbitElements::semiLatusRectum),
            Codec.doubleRange(0, Double.MAX_VALUE).fieldOf("eccentricity").forGetter(OrbitElements::eccentricity),
            Codec.doubleRange(0, Math.PI).fieldOf("inclination").forGetter(OrbitElements::inclination),
            FINITE.fieldOf("longitude_of_ascending_node").forGetter(OrbitElements::longitudeOfAscendingNode),
            FINITE.fieldOf("argument_of_periapsis").forGetter(OrbitElements::argumentOfPeriapsis),
            FINITE.fieldOf("mean_anomaly_at_epoch").forGetter(OrbitElements::meanAnomalyAtEpoch),
            FINITE.fieldOf("epoch_seconds").forGetter(OrbitElements::epochSeconds)
    ).apply(instance, OrbitElements::new));

    public OrbitElements {
        if (!(semiLatusRectum > 0) || !Double.isFinite(semiLatusRectum)
                || !(eccentricity >= 0) || !Double.isFinite(eccentricity)
                || !(inclination >= 0 && inclination <= Math.PI)
                || !Double.isFinite(longitudeOfAscendingNode) || !Double.isFinite(argumentOfPeriapsis)
                || !Double.isFinite(meanAnomalyAtEpoch) || !Double.isFinite(epochSeconds)) {
            throw new IllegalArgumentException("Invalid orbital elements");
        }
    }

    public static OrbitElements fromSemiMajorAxis(double semiMajorAxis, double eccentricity, double inclination,
                                                   double longitudeOfAscendingNode, double argumentOfPeriapsis,
                                                   double meanAnomalyAtEpoch, double epochSeconds) {
        return new OrbitElements(semiMajorAxis * (1 - eccentricity) * (1 + eccentricity), eccentricity, inclination,
                longitudeOfAscendingNode, argumentOfPeriapsis, meanAnomalyAtEpoch, epochSeconds);
    }

    public double semiMajorAxis() {
        return periapsis() / (1 - eccentricity);
    }

    public double periapsis() {
        return semiLatusRectum / (1 + eccentricity);
    }

    public double apoapsis() {
        return eccentricity < 1 ? semiLatusRectum / (1 - eccentricity) : Double.POSITIVE_INFINITY;
    }

    public double period(double mu) {
        return eccentricity < 1 ? TWO_PI / meanMotion(mu, semiMajorAxis()) : Double.POSITIVE_INFINITY;
    }

    /** First inward crossing of the radius at or after the given time, or infinity if there is none. */
    public double inwardCrossing(double mu, double radius, double fromSeconds) {
        double q = periapsis();
        if (radius <= q || radius >= apoapsis()) {
            return Double.POSITIVE_INFINITY;
        }
        double e = eccentricity;
        double mean;
        double rate;
        if (e == 1) {
            double d = -Math.sqrt((radius - q) / q);
            mean = d * (1 + d * d / 3);
            rate = 2 * meanMotion(mu, semiLatusRectum);
        } else {
            boolean hyperbolic = e > 1;
            double a = Math.abs(semiMajorAxis());
            double half = Math.sqrt((radius - q) / (2 * a * e));
            double anomaly = -2 * (hyperbolic ? asinh(half) : Math.asin(Math.min(1, half)));
            mean = kepler(anomaly, e, hyperbolic);
            rate = meanMotion(mu, a);
        }
        double delta = mean - meanAnomalyAtEpoch - rate * (fromSeconds - epochSeconds);
        if (e < 1) {
            delta %= TWO_PI;
            if (delta < 0) {
                delta += TWO_PI;
            }
        }
        return delta >= 0 ? fromSeconds + delta / rate : Double.POSITIVE_INFINITY;
    }

    double maxSpeed(double mu) {
        return Math.sqrt(mu / semiLatusRectum) * (1 + eccentricity);
    }

    public OrbitalState stateAt(double mu, double seconds) {
        if (!(mu > 0) || !Double.isFinite(mu) || !Double.isFinite(seconds)) {
            throw new IllegalArgumentException("Expected a positive gravitational parameter and finite time");
        }
        double elapsed = seconds - epochSeconds;
        double e = eccentricity;
        double q = periapsis();
        double x;
        double y;
        if (e < 1) {
            double a = semiMajorAxis();
            double mean = Math.IEEEremainder(meanAnomalyAtEpoch + meanMotion(mu, a) * elapsed, TWO_PI);
            double anomaly = solveAnomaly(mean, e, false);
            double sinHalf = Math.sin(anomaly / 2);
            x = q - 2 * a * sinHalf * sinHalf;
            y = Math.sqrt(a) * Math.sqrt(semiLatusRectum) * Math.sin(anomaly);
        } else if (e > 1) {
            double a = -semiMajorAxis();
            double mean = meanAnomalyAtEpoch + meanMotion(mu, a) * elapsed;
            double anomaly = solveAnomaly(mean, e, true);
            double sinhHalf = Math.sinh(anomaly / 2);
            x = q - 2 * a * sinhHalf * sinhHalf;
            y = Math.sqrt(a) * Math.sqrt(semiLatusRectum) * Math.sinh(anomaly);
        } else {
            // Barker's equation: M = D + D^3 / 3, with D = tan(trueAnomaly / 2).
            double mean = meanAnomalyAtEpoch + 2 * meanMotion(mu, semiLatusRectum) * elapsed;
            double d = 2 * Math.sinh(asinh(1.5 * mean) / 3);
            x = q * (1 - d * d);
            y = 2 * q * d;
        }

        double radius = Math.hypot(x, y);
        double speed = Math.sqrt(mu / semiLatusRectum);
        var node = new Vec3(Math.cos(longitudeOfAscendingNode), Math.sin(longitudeOfAscendingNode), 0);
        double cosI = Math.cos(inclination);
        var acrossNode = new Vec3(-node.y * cosI, node.x * cosI, Math.sin(inclination));
        double cosW = Math.cos(argumentOfPeriapsis);
        double sinW = Math.sin(argumentOfPeriapsis);
        Vec3 periapsisAxis = node.scale(cosW).add(acrossNode.scale(sinW));
        Vec3 transverseAxis = acrossNode.scale(cosW).subtract(node.scale(sinW));
        return new OrbitalState(
                periapsisAxis.scale(x).add(transverseAxis.scale(y)),
                periapsisAxis.scale(-speed * (y / radius)).add(transverseAxis.scale(speed * (e + x / radius))));
    }

    /** Fit the conic that has this position and velocity at the supplied epoch. */
    public static OrbitElements fromState(double mu, OrbitalState state, double epochSeconds) {
        Vec3 r = state.position();
        Vec3 v = state.velocity();
        double radius = r.length();
        Vec3 h = r.cross(v);
        double momentum = h.length();
        if (!(mu > 0) || !Double.isFinite(mu) || !(radius > 0) || !(momentum > 0)
                || !Double.isFinite(radius) || !Double.isFinite(momentum)) {
            throw new IllegalArgumentException("A Keplerian orbit requires a finite non-radial state");
        }

        double p = momentum * (momentum / mu);
        Vec3 radial = r.scale(1 / radius);
        Vec3 normal = h.scale(1 / momentum);
        Vec3 eccentricityVector = v.cross(h).scale(1 / mu).subtract(radial);
        double e = eccentricityVector.length();
        double nodeLength = Math.hypot(normal.x, normal.y);
        Vec3 node = nodeLength > DEGENERATE_TOLERANCE
                ? new Vec3(-normal.y / nodeLength, normal.x / nodeLength, 0) : new Vec3(1, 0, 0);
        double i = Math.atan2(nodeLength, normal.z);
        double ascendingNode = Math.atan2(node.y, node.x);
        Vec3 periapsis = e > DEGENERATE_TOLERANCE ? eccentricityVector.scale(1 / e) : node;
        double periapsisArgument = directedAngle(node, periapsis, normal);
        double trueAnomaly = directedAngle(periapsis, radial, normal);
        double mean;
        if (e <= DEGENERATE_TOLERANCE) {
            e = 0;
            mean = trueAnomaly;
        } else if (Math.abs(e - 1) <= DEGENERATE_TOLERANCE) {
            e = 1;
            double d = r.dot(v) / Math.sqrt(mu * p);
            mean = d * (1 + d * d / 3);
        } else if (e < 1) {
            double anomaly = Math.atan2(Math.sqrt((1 - e) * (1 + e)) * Math.sin(trueAnomaly), e + Math.cos(trueAnomaly));
            mean = kepler(anomaly, e, false);
        } else {
            double a = p / ((e - 1) * (1 + e));
            double anomaly = asinh(r.dot(v) / (e * Math.sqrt(mu * a)));
            mean = kepler(anomaly, e, true);
        }
        return new OrbitElements(p, e, i, ascendingNode, periapsisArgument, mean, epochSeconds);
    }

    private static double directedAngle(Vec3 from, Vec3 to, Vec3 normal) {
        return Math.atan2(normal.dot(from.cross(to)), from.dot(to));
    }

    private static double meanMotion(double mu, double length) {
        return Math.sqrt(mu / length) / length;
    }

    private static double solveAnomaly(double mean, double e, boolean hyperbolic) {
        if (mean == 0 || e == 0) {
            return mean;
        }
        // Both equations are monotone on the positive half-axis. Keep a bracket for Newton steps near e = 1.
        double target = Math.abs(mean);
        double low = 0;
        double high = hyperbolic ? asinh(target / e) + 1 : Math.PI;
        double anomaly = hyperbolic ? asinh(target / e) : target;
        while (kepler(high, e, hyperbolic) < target) {
            high *= 2;
        }
        for (int step = 0; step < 64; step++) {
            double error = kepler(anomaly, e, hyperbolic) - target;
            double half = hyperbolic ? Math.sinh(anomaly / 2) : Math.sin(anomaly / 2);
            double derivative = Math.abs(1 - e) + 2 * e * half * half;
            double correction = error / derivative;
            if (Math.abs(correction) <= ANGLE_TOLERANCE) {
                break;
            }
            if (error > 0) {
                high = anomaly;
            } else {
                low = anomaly;
            }
            double next = anomaly - correction;
            anomaly = next > low && next < high ? next : (low + high) / 2;
        }
        return Math.copySign(anomaly, mean);
    }

    private static double kepler(double anomaly, double e, boolean hyperbolic) {
        if (Math.abs(anomaly) < 0.1) {
            // Avoid cancellation of E - sin(E) and sinh(H) - H near periapsis.
            double x2 = anomaly * anomaly;
            double signedX2 = hyperbolic ? x2 : -x2;
            double remainder = anomaly * x2 / 6 * (1 + signedX2 / 20
                    * (1 + signedX2 / 42 * (1 + signedX2 / 72)));
            return Math.abs(1 - e) * anomaly + e * remainder;
        }
        return hyperbolic ? e * Math.sinh(anomaly) - anomaly : anomaly - e * Math.sin(anomaly);
    }

    private static double asinh(double value) {
        double abs = Math.abs(value);
        return Math.copySign(Math.log1p(abs + abs * (abs / (1 + Math.hypot(1, abs)))), value);
    }
}

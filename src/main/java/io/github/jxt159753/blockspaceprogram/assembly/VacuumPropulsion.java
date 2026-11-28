package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3d;

/** Wet mass, total fuel and the independently fed engine groups of one active stage. */
public record VacuumPropulsion(double wetMass, double fuel, List<Group> groups) {
    static final double STANDARD_GRAVITY = 9.80665;
    public VacuumPropulsion {
        groups = List.copyOf(groups);
    }

    static VacuumPropulsion fromGroups(double wetMass, double fuel, double[] capacity, Vector3d[] thrust, double[] flow) {
        var groups = new ArrayList<Group>();
        for (int i = 0; i < capacity.length; i++) {
            if (capacity[i] > 0 && flow[i] > 0 && Double.isFinite(capacity[i] / flow[i])) {
                groups.add(new Group(capacity[i], thrust[i].x, thrust[i].y, thrust[i].z, flow[i]));
            }
        }
        return new VacuumPropulsion(wetMass, fuel, groups);
    }

    double thrustToWeight() {
        return wetMass > 0 ? netThrust() / (wetMass * STANDARD_GRAVITY) : 0;
    }

    public double netThrust() {
        var thrust = new Vector3d();
        for (Group group : groups) {
            thrust.add(group.thrustX(), group.thrustY(), group.thrustZ());
        }
        return thrust.length();
    }

    public boolean thrustCancels() {
        return !groups.isEmpty() && netThrust() == 0;
    }

    /** Integrate the rocket equation piecewise as fuel groups run dry. */
    public Burn estimate(double requestedDeltaV) {
        return estimate(requestedDeltaV, Double.POSITIVE_INFINITY);
    }

    Burn estimate(double requestedDeltaV, double timeLimit) {
        double delivered = 0;
        double elapsed = 0;
        double consumed = 0;
        var thrust = new Vector3d();
        while (delivered < requestedDeltaV && elapsed < timeLimit) {
            double mass = wetMass - consumed;
            double end = timeLimit;
            thrust.zero();
            double flow = 0;
            for (Group group : groups) {
                double depletion = group.fuel() / group.flow();
                if (depletion > elapsed) {
                    end = Math.min(end, depletion);
                    thrust.add(group.thrustX(), group.thrustY(), group.thrustZ());
                    flow += group.flow();
                }
            }
            if (!Double.isFinite(end) || !(flow > 0) || !Double.isFinite(flow) || !(mass > 0) || !Double.isFinite(mass)) {
                break;
            }
            double exhaust = thrust.length() / flow;
            double used = (end - elapsed) * flow;
            double deltaV = exhaust == 0 ? 0 : exhaust * Math.log1p(used / Math.max(0, mass - used));
            double required = requestedDeltaV - delivered;
            if (exhaust > 0 && Double.isFinite(requestedDeltaV) && required <= deltaV) {
                double amount = Math.min(used, -mass * Math.expm1(-required / exhaust));
                return new Burn(requestedDeltaV, elapsed + amount / flow, consumed + amount);
            }
            delivered += deltaV;
            elapsed = end;
            consumed += used;
        }
        return new Burn(delivered, elapsed, consumed);
    }

    static double fractionRemaining(double fuel, double flow, double seconds) {
        return seconds >= fuel / flow ? 0 : 1 - seconds * flow / fuel;
    }

    public record Burn(double deltaV, double seconds, double fuel) {
        public boolean supplies(double requested) {
            return deltaV >= requested && seconds > 0 && Double.isFinite(seconds);
        }
    }

    public record Group(double fuel, double thrustX, double thrustY, double thrustZ, double flow) {
        public Group(double fuel, double thrust, double flow) {
            this(fuel, thrust, 0, 0, flow);
        }
    }
}

package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Wet mass, remaining propellant and the independently fueled engine groups. */
public record VacuumPropulsion(double wetMass, double fuel, double electric, List<Group> groups) {
    static final double STANDARD_GRAVITY = 9.80665;

    public VacuumPropulsion {
        groups = List.copyOf(groups);
    }

    double thrustToWeight() {
        return wetMass > 0 ? netThrust() / (wetMass * STANDARD_GRAVITY) : 0;
    }

    public double netThrust() {
        var thrust = new Vector3d();
        for (Group group : groups) {
            addThrust(thrust, group, electric > 0);
        }
        return thrust.length();
    }

    public boolean thrustCancels() {
        return groups.stream().anyMatch(group -> group.chemical().flow() > 0
                || electric > 0 && group.electric().flow() > 0) && netThrust() == 0;
    }

    /** Tests whether more electricity would make an otherwise unaffordable burn possible. */
    public boolean electricityLimits(double requestedDeltaV) {
        return groups.stream().anyMatch(group -> group.electricPower() > 0)
                && estimate(requestedDeltaV, null, Double.POSITIVE_INFINITY).supplies(requestedDeltaV);
    }

    public Burn estimate(double requestedDeltaV) {
        return estimate(requestedDeltaV, null, electric);
    }

    public BurnSimulation startBurn() {
        return new BurnSimulation(electric);
    }

    Burn burnStage(boolean[] separatingGroups) {
        return estimate(Double.POSITIVE_INFINITY, separatingGroups, electric);
    }

    /** Integrate the rocket equation as fuel groups or the common battery run dry. */
    private Burn estimate(double requestedDeltaV, boolean[] separatingGroups, double charge) {
        var simulation = new BurnSimulation(charge);
        boolean burnToSeparation = feedingSeparatingGroup(separatingGroups, simulation.supply);
        while (simulation.delivered < requestedDeltaV) {
            var segment = simulation.nextSegment(requestedDeltaV - simulation.delivered, Double.POSITIVE_INFINITY);
            if (segment.seconds() == 0) {
                break;
            }
            simulation.consume(segment);
            if (burnToSeparation && !feedingSeparatingGroup(separatingGroups, simulation.supply)) {
                break;
            }
        }
        return simulation.result();
    }

    public record Segment(double seconds, double deltaV) {
        private static final Segment STOPPED = new Segment(0, 0);
    }

    /** Tracks a burn across time limits and propellant cutoffs without rebuilding the engine groups. */
    public final class BurnSimulation {
        private final PropellantFlow supply = new PropellantFlow(groups.size());
        private final Vector3d thrust = new Vector3d();
        private final double initialCharge;
        private double elapsed;
        private double consumed;
        private double delivered;

        private BurnSimulation(double charge) {
            initialCharge = charge;
            supply.reset(charge);
            for (int i = 0; i < groups.size(); i++) {
                var group = groups.get(i);
                supply.addFuel(i, group.fuel());
                supply.addEngine(i, group.chemical().flow(), 0);
                supply.addEngine(i, group.electric().flow(), group.electricPower());
            }
        }

        /** Solve the next segment with a constant set of powered engines. */
        public Segment nextSegment(double requestedDeltaV, double maxSeconds) {
            double mass = wetMass - consumed;
            double seconds = supply.nextSegment(maxSeconds);
            double flow = supply.flowRate();
            if (!(seconds > 0) || !Double.isFinite(seconds) || !(flow > 0) || !Double.isFinite(flow)
                    || !(mass > 0) || !Double.isFinite(mass)) {
                return Segment.STOPPED;
            }
            thrust.zero();
            for (int i = 0; i < groups.size(); i++) {
                if (supply.feeding(i)) {
                    addThrust(thrust, groups.get(i), supply.electric() > 0);
                }
            }
            double exhaust = thrust.length() / flow;
            double used = seconds * flow;
            double deltaV = exhaust == 0 ? 0 : exhaust * Math.log1p(used / Math.max(0, mass - used));
            if (Double.isFinite(requestedDeltaV) && requestedDeltaV < deltaV) {
                seconds = Math.min(seconds, -mass * Math.expm1(-requestedDeltaV / exhaust) / flow);
                deltaV = requestedDeltaV;
            }
            return new Segment(seconds, deltaV);
        }

        /** Settle the segment returned by the most recent call to nextSegment. */
        public void consume(Segment segment) {
            consumed += segment.seconds() * supply.flowRate();
            supply.consume(segment.seconds());
            elapsed += segment.seconds();
            delivered += segment.deltaV();
        }

        public double remainingFuel() {
            return Math.max(0, fuel - consumed);
        }

        public double remainingElectric() {
            return supply.electric();
        }

        /** Whether an engine still has fuel but has run out of power. */
        public boolean outOfElectricity() {
            if (supply.electric() == 0) {
                for (int i = 0; i < groups.size(); i++) {
                    if (supply.fuel(i) > 0 && groups.get(i).electricPower() > 0) {
                        return true;
                    }
                }
            }
            return false;
        }

        public Burn result() {
            var remaining = new HashMap<Integer, Double>();
            for (int i = 0; i < groups.size(); i++) {
                var group = groups.get(i);
                remaining.put(group.index(), supply.fuel(i) / group.fuel());
            }
            double usedElectric = Double.isFinite(initialCharge) ? initialCharge - supply.electric() : 0;
            return new Burn(delivered, elapsed, consumed, usedElectric, supply.electric(), remaining);
        }
    }

    private boolean feedingSeparatingGroup(boolean[] separatingGroups, PropellantFlow supply) {
        if (separatingGroups != null) {
            for (int i = 0; i < groups.size(); i++) {
                if (separatingGroups[groups.get(i).index()] && supply.feeding(i)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addThrust(Vector3d thrust, Group group, boolean powered) {
        group.chemical().addTo(thrust);
        if (powered) {
            group.electric().addTo(thrust);
        }
    }

    public record Burn(double deltaV, double seconds, double fuel, double electric, double remainingElectric,
                       Map<Integer, Double> remainingFuelFractions) {
        public Burn {
            remainingFuelFractions = Map.copyOf(remainingFuelFractions);
        }

        public boolean supplies(double requested) {
            return deltaV >= requested && seconds > 0 && Double.isFinite(seconds);
        }

        double fuelFraction(int group) {
            return remainingFuelFractions.getOrDefault(group, 1.0);
        }

        double electricFraction(double initial) {
            return initial > 0 ? remainingElectric / initial : 0;
        }
    }

    public record Group(int index, double fuel, Drive chemical, Drive electric, double electricPower) {}

    public record Drive(double thrustX, double thrustY, double thrustZ, double flow) {
        static final Drive OFF = new Drive(0, 0, 0, 0);

        void addTo(Vector3d thrust) {
            thrust.add(thrustX, thrustY, thrustZ);
        }

        Drive add(Vector3dc thrust, double flow) {
            return new Drive(thrustX + thrust.x(), thrustY + thrust.y(), thrustZ + thrust.z(), this.flow + flow);
        }
    }

    static final class Builder {
        private final Drive[] chemical;
        private final Drive[] electric;
        private final double[] power;

        Builder(int count) {
            chemical = new Drive[count];
            electric = new Drive[count];
            power = new double[count];
            Arrays.fill(chemical, Drive.OFF);
            Arrays.fill(electric, Drive.OFF);
        }

        void addEngine(int group, Vector3dc thrust, double flow, double draw) {
            if (draw > 0) {
                electric[group] = electric[group].add(thrust, flow);
                power[group] += draw;
            } else {
                chemical[group] = chemical[group].add(thrust, flow);
            }
        }

        VacuumPropulsion build(double wetMass, double[] fuel, double charge) {
            var groups = new ArrayList<Group>();
            for (int i = 0; i < fuel.length; i++) {
                if (fuel[i] > 0 && (chemical[i].flow() > 0 || electric[i].flow() > 0)) {
                    groups.add(new Group(i, fuel[i], chemical[i], electric[i], power[i]));
                }
            }
            return new VacuumPropulsion(wetMass, Arrays.stream(fuel).sum(), charge, groups);
        }
    }
}

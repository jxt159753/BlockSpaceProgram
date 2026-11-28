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

    public double netThrust() {
        return thrustVector().length();
    }

    Vector3d thrustVector() {
        var thrust = new Vector3d();
        for (Group group : groups) {
            addThrust(thrust, group, electric > 0);
        }
        return thrust;
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

    Burn burnStage(boolean[] separatingGroups) {
        return estimate(Double.POSITIVE_INFINITY, separatingGroups, electric);
    }

    /** Integrate the rocket equation as fuel groups or the common battery run dry. */
    private Burn estimate(double requestedDeltaV, boolean[] separatingGroups, double charge) {
        var supply = new PropellantFlow(groups.size());
        supply.reset(charge);
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            supply.addFuel(i, group.fuel());
            supply.addEngine(i, group.chemical().flow(), 0);
            supply.addEngine(i, group.electric().flow(), group.electricPower());
        }
        boolean burnToSeparation = feedingSeparatingGroup(separatingGroups, supply);
        double delivered = 0;
        double elapsed = 0;
        double consumed = 0;
        var thrust = new Vector3d();
        while (delivered < requestedDeltaV) {
            double mass = wetMass - consumed;
            double seconds = supply.nextSegment(Double.POSITIVE_INFINITY);
            double flow = supply.flowRate();
            if (!(seconds > 0) || !Double.isFinite(seconds) || !(flow > 0) || !Double.isFinite(flow)
                    || !(mass > 0) || !Double.isFinite(mass)) {
                break;
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
            double required = requestedDeltaV - delivered;
            boolean reached = Double.isFinite(requestedDeltaV) && required <= deltaV;
            if (reached && required < deltaV) {
                seconds = Math.min(seconds, -mass * Math.expm1(-required / exhaust) / flow);
            }
            supply.consume(seconds);
            elapsed += seconds;
            consumed += seconds * flow;
            delivered = reached ? requestedDeltaV : delivered + deltaV;
            if (burnToSeparation && !feedingSeparatingGroup(separatingGroups, supply)) {
                break;
            }
        }
        var remaining = new HashMap<Integer, Double>();
        for (int i = 0; i < groups.size(); i++) {
            var group = groups.get(i);
            remaining.put(group.index(), supply.fuel(i) / group.fuel());
        }
        double usedElectric = Double.isFinite(charge) ? charge - supply.electric() : 0;
        return new Burn(delivered, elapsed, consumed, usedElectric, supply.electric(), remaining);
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

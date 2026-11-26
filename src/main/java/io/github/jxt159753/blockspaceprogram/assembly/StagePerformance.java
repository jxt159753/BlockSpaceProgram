package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Vacuum estimates with full tanks at launch and no fuel flow across a separable link. */
public record StagePerformance(double deltaV, double thrustToWeight, double burnTime) {
    private static final double STANDARD_GRAVITY = 9.80665;

    public static List<StagePerformance> calculate(ShipDesign design) {
        int count = design.nodes().size();
        double[] fuel = new double[count];
        for (int i = 0; i < count; i++) {
            fuel[i] = design.nodes().get(i).part().properties().fuelCapacity().values().stream()
                    .mapToDouble(Double::doubleValue).sum();
        }

        Set<Integer> detached = new HashSet<>();
        List<StagePerformance> performance = new ArrayList<>(design.stageCount());
        for (int stage = 0; stage < design.stageCount(); stage++) {
            for (int i = 0; i < design.links().size(); i++) {
                if (design.links().get(i).separationStage() == stage) {
                    detached.add(i);
                }
            }

            int[] group = new int[count];
            Arrays.fill(group, -1);
            double[] capacity = new double[count];
            double[] thrust = new double[count];
            double[] flow = new double[count];
            double wetMass = 0;
            for (ShipDesign.Branch branch : design.tree(detached)) {
                int index = branch.node();
                var node = design.nodes().get(index);
                var properties = node.part().properties();
                int tankGroup = branch.link() < 0 || design.links().get(branch.link()).separable() ? index : group[branch.parent()];
                group[index] = tankGroup;
                capacity[tankGroup] += fuel[index];
                wetMass += properties.mass() + fuel[index];
                if (node.engineStage() == stage && properties.vacuumSpecificImpulse() > 0 && properties.thrust() > 0) {
                    thrust[tankGroup] += properties.thrust();
                    flow[tankGroup] += properties.thrust() / (STANDARD_GRAVITY * properties.vacuumSpecificImpulse());
                }
            }

            double[] remaining = capacity.clone();
            performance.add(burn(wetMass, thrust, flow, remaining));
            for (int i = 0; i < count; i++) {
                int tankGroup = group[i];
                if (tankGroup >= 0 && capacity[tankGroup] > 0) {
                    fuel[i] *= remaining[tankGroup] / capacity[tankGroup];
                }
            }
        }
        return List.copyOf(performance);
    }

    private static StagePerformance burn(double mass, double[] thrust, double[] flow, double[] fuel) {
        double deltaV = 0;
        double twr = 0;
        double time = 0;
        while (mass > 0) {
            double interval = Double.POSITIVE_INFINITY;
            double thrustSum = 0;
            double flowSum = 0;
            for (int i = 0; i < fuel.length; i++) {
                if (fuel[i] > 0 && flow[i] > 0) {
                    interval = Math.min(interval, fuel[i] / flow[i]);
                    thrustSum += thrust[i];
                    flowSum += flow[i];
                }
            }
            if (!Double.isFinite(interval) || !Double.isFinite(flowSum) || !Double.isFinite(mass)) {
                break;
            }
            if (time == 0) {
                twr = thrustSum / (mass * STANDARD_GRAVITY);
            }
            double used = 0;
            for (int i = 0; i < fuel.length; i++) {
                if (fuel[i] > 0 && flow[i] > 0) {
                    double amount = fuel[i] / flow[i] <= interval ? fuel[i] : interval * flow[i];
                    fuel[i] -= amount;
                    used += amount;
                }
            }
            deltaV += thrustSum / flowSum * Math.log1p(used / (mass - used));
            time += interval;
            mass -= used;
        }
        return new StagePerformance(deltaV, twr, time);
    }
}

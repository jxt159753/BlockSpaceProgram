package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Vacuum estimates with full tanks at launch and no fuel flow across a separable link. */
public record StagePerformance(double deltaV, double thrustToWeight, double burnTime) {
    public static List<StagePerformance> calculate(ShipDesign design) {
        int count = design.nodes().size();
        double[] fuel = new double[count];
        for (int i = 0; i < count; i++) {
            fuel[i] = fuelCapacity(design.nodes().get(i));
        }

        Set<Integer> detached = new HashSet<>();
        int[] group = tankGroups(design);
        List<StagePerformance> performance = new ArrayList<>(design.stageCount());
        for (int stage = 0; stage < design.stageCount(); stage++) {
            for (int i = 0; i < design.links().size(); i++) {
                if (design.links().get(i).separationStage() == stage) {
                    detached.add(i);
                }
            }

            double[] capacity = new double[count];
            double[] thrust = new double[count];
            double[] flow = new double[count];
            double wetMass = 0;
            boolean[] attached = new boolean[count];
            for (ShipDesign.Branch branch : design.tree(detached)) {
                int index = branch.node();
                var node = design.nodes().get(index);
                int tankGroup = group[index];
                attached[index] = true;
                capacity[tankGroup] += fuel[index];
                wetMass += node.dryMass() + fuel[index];
                if (node.engineStage() == stage && ShipFlightState.hasEngine(node)) {
                    var properties = node.part().properties();
                    thrust[tankGroup] += properties.thrust();
                    flow[tankGroup] += fuelFlow(properties.thrust(), properties.vacuumSpecificImpulse());
                }
            }

            var propulsion = VacuumPropulsion.fromGroups(wetMass, Arrays.stream(capacity).sum(), capacity, thrust, flow);
            var burn = propulsion.estimate(Double.POSITIVE_INFINITY);
            performance.add(new StagePerformance(burn.deltaV(), propulsion.thrustToWeight(), burn.seconds()));
            for (int i = 0; i < count; i++) {
                int tankGroup = group[i];
                if (attached[i] && capacity[tankGroup] > 0) {
                    fuel[i] *= VacuumPropulsion.fractionRemaining(capacity[tankGroup], flow[tankGroup], burn.seconds());
                }
            }
        }
        return List.copyOf(performance);
    }

    static int[] tankGroups(ShipDesign design) {
        var separators = new HashSet<Integer>();
        for (int i = 0; i < design.links().size(); i++) {
            if (design.links().get(i).separable()) {
                separators.add(i);
            }
        }
        int[] groups = new int[design.nodes().size()];
        for (var group : design.components(separators)) {
            for (int node : group) {
                groups[node] = group.getFirst();
            }
        }
        return groups;
    }

    static double fuelFlow(double thrust, double isp) {
        return thrust / (VacuumPropulsion.STANDARD_GRAVITY * isp);
    }

    static double fuelCapacity(ShipDesign.Node node) {
        return node.part() == null ? 0
                : node.part().properties().fuelCapacity().values().stream().mapToDouble(Double::doubleValue).sum();
    }
}

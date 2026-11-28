package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.joml.Vector3d;

/** Vacuum estimates with full tanks at launch and no fuel flow across a separable link. */
public record StagePerformance(double deltaV, double thrustToWeight, double burnTime) {
    public static List<StagePerformance> calculate(ShipDesign design) {
        int count = design.nodes().size();
        double[] fuel = new double[count];
        var engineThrust = new Vector3d[count];
        for (int i = 0; i < count; i++) {
            var node = design.nodes().get(i);
            fuel[i] = fuelCapacity(node);
            if (ShipFlightState.hasEngine(node)) {
                engineThrust[i] = ShipFlightState.vacuumThrust(node);
            }
        }

        Set<Integer> detached = new HashSet<>();
        int[] group = tankGroups(design);
        List<StagePerformance> performance = new ArrayList<>(design.stageCount());
        var attached = afterSeparation(design, detached, 0);
        for (int stage = 0; stage < design.stageCount(); stage++) {
            double[] capacity = new double[count];
            var thrust = new Vector3d[count];
            Arrays.setAll(thrust, index -> new Vector3d());
            double[] flow = new double[count];
            double wetMass = 0;
            for (ShipDesign.Branch branch : attached) {
                int index = branch.node();
                var node = design.nodes().get(index);
                int tankGroup = group[index];
                capacity[tankGroup] += fuel[index];
                wetMass += node.dryMass() + fuel[index];
                if (engineThrust[index] != null && ShipFlightState.hasIgnited(node.engineStage(), stage)) {
                    var properties = node.part().properties();
                    thrust[tankGroup].add(engineThrust[index]);
                    flow[tankGroup] += fuelFlow(properties.thrust(), properties.vacuumSpecificImpulse());
                }
            }

            var next = afterSeparation(design, detached, stage + 1);
            boolean[] retained = new boolean[count];
            for (var branch : next) {
                retained[branch.node()] = true;
            }
            double separationTime = 0;
            for (var branch : attached) {
                int tankGroup = group[branch.node()];
                if (!retained[branch.node()] && capacity[tankGroup] > 0 && flow[tankGroup] > 0) {
                    separationTime = Math.max(separationTime, capacity[tankGroup] / flow[tankGroup]);
                }
            }
            // Predict separation once the last fueled group being dropped runs dry.
            double timeLimit = separationTime > 0 ? separationTime : Double.POSITIVE_INFINITY;
            var propulsion = VacuumPropulsion.fromGroups(wetMass, Arrays.stream(capacity).sum(), capacity, thrust, flow);
            var burn = propulsion.estimate(Double.POSITIVE_INFINITY, timeLimit);
            performance.add(new StagePerformance(burn.deltaV(), propulsion.thrustToWeight(), burn.seconds()));
            for (var branch : next) {
                int i = branch.node();
                int tankGroup = group[i];
                if (capacity[tankGroup] > 0) {
                    fuel[i] *= VacuumPropulsion.fractionRemaining(capacity[tankGroup], flow[tankGroup], burn.seconds());
                }
            }
            attached = next;
        }
        return List.copyOf(performance);
    }

    private static List<ShipDesign.Branch> afterSeparation(ShipDesign design, Set<Integer> detached, int stage) {
        for (int i = 0; i < design.links().size(); i++) {
            if (design.links().get(i).separationStage() == stage) {
                detached.add(i);
            }
        }
        return design.tree(detached);
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

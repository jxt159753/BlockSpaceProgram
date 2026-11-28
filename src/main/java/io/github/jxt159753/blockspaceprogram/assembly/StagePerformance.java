package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/** Vacuum estimates with full tanks at launch and no fuel flow across a separable link. */
public record StagePerformance(double deltaV, double thrustToWeight, double burnTime, Vector3dc thrust) {
    public static List<StagePerformance> calculate(ShipDesign design) {
        int count = design.nodes().size();
        double[] fuel = new double[count];
        double[] electric = new double[count];
        for (int i = 0; i < count; i++) {
            var node = design.nodes().get(i);
            fuel[i] = fuelCapacity(node);
            electric[i] = electricCapacity(node);
        }

        Set<Integer> detached = new HashSet<>();
        int[] group = tankGroups(design);
        List<StagePerformance> performance = new ArrayList<>(design.stageCount());
        var attached = afterSeparation(design, detached, 0);
        for (int stage = 0; stage < design.stageCount(); stage++) {
            double[] capacity = new double[count];
            var engines = new VacuumPropulsion.Builder(count);
            double wetMass = 0;
            double charge = 0;
            for (ShipDesign.Branch branch : attached) {
                int index = branch.node();
                var node = design.nodes().get(index);
                int tankGroup = group[index];
                capacity[tankGroup] += fuel[index];
                wetMass += node.dryMass() + fuel[index];
                charge += electric[index];
                if (ShipFlightState.hasIgnited(node.engineStage(), stage)) {
                    addVacuumEngines(engines, tankGroup, node);
                }
            }

            var next = afterSeparation(design, detached, stage + 1);
            boolean[] retained = new boolean[count];
            for (var branch : next) {
                retained[branch.node()] = true;
            }
            boolean[] separatingGroups = new boolean[count];
            for (var branch : attached) {
                if (!retained[branch.node()]) {
                    separatingGroups[group[branch.node()]] = true;
                }
            }
            var propulsion = engines.build(wetMass, capacity, charge);
            var burn = propulsion.burnStage(separatingGroups);
            var thrust = propulsion.thrustVector();
            double thrustToWeight = wetMass > 0 ? thrust.length() / (wetMass * VacuumPropulsion.STANDARD_GRAVITY) : 0;
            performance.add(new StagePerformance(burn.deltaV(), thrustToWeight, burn.seconds(), thrust));
            double electricFraction = burn.electricFraction(charge);
            for (var branch : next) {
                int i = branch.node();
                fuel[i] *= burn.fuelFraction(group[i]);
                electric[i] *= electricFraction;
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

    static void addVacuumEngines(VacuumPropulsion.Builder engines, int group, ShipDesign.Node node) {
        for (var engine : node.part().engines()) {
            var direction = ShipFlightState.thrustDirection(node, engine);
            var rating = engine.performance();
            var thrust = new Vector3d(direction.x, direction.y, direction.z).mul(rating.thrust());
            engines.addEngine(group, thrust, fuelFlow(rating.thrust(), rating.vacuumSpecificImpulse()), engine.electricPower());
        }
    }

    static double fuelCapacity(ShipDesign.Node node) {
        return node.part() == null ? 0
                : node.part().properties().fuelCapacity().values().stream().mapToDouble(Double::doubleValue).sum();
    }

    static double electricCapacity(ShipDesign.Node node) {
        return node.part() == null ? 0 : node.part().properties().electricCapacity();
    }
}

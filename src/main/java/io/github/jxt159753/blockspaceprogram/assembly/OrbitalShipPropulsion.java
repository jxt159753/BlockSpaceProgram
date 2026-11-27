package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Reads and writes the same per-part fuel saved by the local flight entity. */
public final class OrbitalShipPropulsion {
    private final CompoundTag shipData;
    private final double[] partFuel;
    private final int[] groups;
    private final double[] groupFuel;
    private final double[] flow;
    private final VacuumPropulsion propulsion;

    private OrbitalShipPropulsion(CompoundTag shipData, ShipDesign design, int stage, ListTag savedFuel, long damage) {
        this.shipData = shipData;
        int count = design.nodes().size();
        partFuel = new double[count];
        groups = new int[count];
        groupFuel = new double[count];
        flow = new double[count];
        double[] thrust = new double[count];
        double dryMass = 0;
        double fuel = 0;
        for (ShipDesign.Branch branch : design.tree(Set.of())) {
            int index = branch.node();
            var node = design.nodes().get(index);
            var properties = node.part().properties();
            int group = StagePerformance.tankGroup(design, branch, groups);
            groups[index] = group;
            boolean damaged = (damage & (1L << index)) != 0;
            partFuel[index] = ShipFlightState.initialFuel(savedFuel, count, index, StagePerformance.fuelCapacity(node), damaged);
            groupFuel[group] += partFuel[index];
            fuel += partFuel[index];
            dryMass += properties.mass();
            if (!damaged && node.engineStage() == stage && ShipFlightState.hasEngine(node)) {
                thrust[group] += properties.thrust();
                flow[group] += StagePerformance.fuelFlow(properties.thrust(), properties.vacuumSpecificImpulse());
            }
        }
        propulsion = VacuumPropulsion.fromGroups(ShipFlightState.flightDryMass(dryMass) + fuel, fuel, groupFuel, thrust, flow);
    }

    @Nullable
    public static OrbitalShipPropulsion read(HolderLookup.Provider registries, CompoundTag shipData) {
        if (!shipData.contains("Blueprint", Tag.TAG_COMPOUND)) {
            return null;
        }
        ItemStack blueprint = ItemStack.parseOptional(registries, shipData.getCompound("Blueprint"));
        ShipDesign design = AssembledShipEntity.design(blueprint);
        if (design == null || design.nodes().stream().noneMatch(ShipDesign.Node::isCommandPod)) {
            return null;
        }
        int stage = shipData.contains("FlightStage") ? Math.min(shipData.getInt("FlightStage"), design.stageCount() - 1) : -2;
        if (stage < -1) {
            stage = ShipFlightState.initialStage(design);
        }
        return new OrbitalShipPropulsion(shipData, design, stage, shipData.getList("Fuel", Tag.TAG_DOUBLE),
                shipData.getLong("DamagedParts"));
    }

    public VacuumPropulsion propulsion() {
        return propulsion;
    }

    public CompoundTag afterBurn(double seconds) {
        var fuel = new ListTag();
        for (int i = 0; i < partFuel.length; i++) {
            int group = groups[i];
            double fraction = groupFuel[group] > 0
                    ? VacuumPropulsion.fractionRemaining(groupFuel[group], flow[group], seconds) : 0;
            fuel.add(DoubleTag.valueOf(partFuel[i] * fraction));
        }
        var updated = shipData.copy();
        updated.put("Fuel", fuel);
        return updated;
    }
}

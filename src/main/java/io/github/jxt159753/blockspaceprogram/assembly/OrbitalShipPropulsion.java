package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.Arrays;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/** Reads and writes the same per-part propellant saved by the local flight entity. */
public final class OrbitalShipPropulsion {
    private final CompoundTag shipData;
    private final double[] partFuel;
    private final double[] partElectric;
    private final int[] groups;
    private final VacuumPropulsion propulsion;
    private final int stage;

    private OrbitalShipPropulsion(CompoundTag shipData, ShipDesign design, int stage, ShipPropellantData saved, long damage) {
        this.shipData = shipData;
        this.stage = stage;
        int count = design.nodes().size();
        partFuel = new double[count];
        partElectric = new double[count];
        groups = StagePerformance.tankGroups(design);
        double[] groupFuel = new double[count];
        var engines = new VacuumPropulsion.Builder(count);
        double dryMass = 0;
        for (ShipDesign.Branch branch : design.tree(Set.of())) {
            int index = branch.node();
            var node = design.nodes().get(index);
            int group = groups[index];
            boolean damaged = (damage & (1L << index)) != 0;
            partFuel[index] = ShipPropellantData.initialAmount(saved.fuel(), count, index,
                    StagePerformance.fuelCapacity(node), damaged);
            partElectric[index] = ShipPropellantData.initialAmount(saved.electric(), count, index,
                    StagePerformance.electricCapacity(node), damaged);
            groupFuel[group] += partFuel[index];
            dryMass += node.dryMass();
            if (!damaged && ShipFlightState.hasIgnited(node.engineStage(), stage) && ShipFlightState.hasEngine(node)) {
                var properties = node.part().properties();
                engines.addEngine(group, ShipFlightState.vacuumThrust(node),
                        StagePerformance.fuelFlow(properties.thrust(), properties.vacuumSpecificImpulse()), properties.electricPower());
            }
        }
        propulsion = engines.build(ShipFlightState.flightDryMass(dryMass) + Arrays.stream(partFuel).sum(),
                groupFuel, Arrays.stream(partElectric).sum());
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
        return new OrbitalShipPropulsion(shipData, design, stage, ShipPropellantData.read(shipData),
                shipData.getLong("DamagedParts"));
    }

    public VacuumPropulsion propulsion() {
        return propulsion;
    }

    public int stage() {
        return stage;
    }

    public CompoundTag afterBurn(VacuumPropulsion.Burn burn) {
        var fuel = new ListTag();
        var electric = new ListTag();
        double electricFraction = burn.electricFraction(propulsion.electric());
        for (int i = 0; i < partFuel.length; i++) {
            fuel.add(DoubleTag.valueOf(partFuel[i] * burn.fuelFraction(groups[i])));
            electric.add(DoubleTag.valueOf(partElectric[i] * electricFraction));
        }
        var updated = shipData.copy();
        new ShipPropellantData(fuel, electric).write(updated);
        return updated;
    }
}

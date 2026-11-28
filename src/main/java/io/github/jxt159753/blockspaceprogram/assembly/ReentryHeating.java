package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import java.util.Arrays;
import java.util.BitSet;
import java.util.function.IntPredicate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

/** Transient node temperatures and the windward projection after hull burn-through. */
final class ReentryHeating {
    private static final double FLAME_TEMPERATURE = ReentryTemperature.AMBIENT_KELVIN + 50;

    private final ShipDesign design;
    private final BitSet hullNodes = new BitSet();
    private final BitSet burnedHull = new BitSet();
    private final BitSet overheated = new BitSet();
    private final double[] dryMass;
    private final double[] resistance;
    private final double[] temperature;
    private AeroProjection projection;
    private double maximumRatio;
    private boolean hotWindwardSurface;

    ReentryHeating(ShipDesign design, AeroProjection aerodynamics, double defaultResistance, IntPredicate isDamaged) {
        this.design = design;
        projection = aerodynamics;
        int count = design.nodes().size();
        dryMass = new double[count];
        resistance = new double[count];
        temperature = new double[count];
        Arrays.fill(temperature, ReentryTemperature.AMBIENT_KELVIN);
        for (int index = 0; index < count; index++) {
            var node = design.nodes().get(index);
            dryMass[index] = node.dryMass();
            double limit;
            if (node.hull() != null) {
                hullNodes.set(index);
                limit = hullResistance(node.hull(), defaultResistance);
            } else {
                double baked = node.part().properties().heatResistance();
                limit = baked > 0 ? baked : defaultResistance;
            }
            resistance[index] = limit;
            maximumRatio = Math.max(maximumRatio, temperature[index] / limit);
        }
        updateProjection(isDamaged);
    }

    private static double hullResistance(HullRegion hull, double defaultResistance) {
        double[] minimum = {Double.POSITIVE_INFINITY};
        hull.forEach((pos, material) -> {
            double limit = BlockPartProperties.get(material.state()).heatResistance();
            if (limit > 0) {
                minimum[0] = Math.min(minimum[0], limit);
            }
        });
        return Double.isFinite(minimum[0]) ? minimum[0] : defaultResistance;
    }

    void updateProjection(IntPredicate isDamaged) {
        boolean changed = false;
        for (int index = hullNodes.nextSetBit(0); index >= 0; index = hullNodes.nextSetBit(index + 1)) {
            if (!burnedHull.get(index) && isDamaged.test(index)) {
                burnedHull.set(index);
                changed = true;
            }
        }
        if (changed) {
            projection = ShipAerodynamics.project(design, burnedHull::get);
        }
    }

    BitSet step(@Nullable Vector3dc windward, double density, double speed, double multiplier, double seconds,
            double[] fuel) {
        double equilibrium = ReentryTemperature.equilibrium(speed, multiplier);
        double flow = windward != null && multiplier > 0 ? density * speed : 0;
        overheated.clear();
        maximumRatio = 0;
        hotWindwardSurface = false;
        for (int index = 0; index < temperature.length; index++) {
            double nodeFlow = flow > 0 ? flow * projection.exposedArea(windward, index) : 0;
            double next = ReentryTemperature.step(temperature[index], equilibrium, nodeFlow, dryMass[index] + fuel[index], seconds);
            temperature[index] = next;
            maximumRatio = Math.max(maximumRatio, next / resistance[index]);
            if (next > resistance[index]) {
                overheated.set(index);
            }
            hotWindwardSurface |= nodeFlow > 0 && next >= FLAME_TEMPERATURE;
        }
        return overheated;
    }

    void copyNode(ReentryHeating source, int from, int to) {
        temperature[to] = source.temperature[from];
        maximumRatio = Math.max(maximumRatio, temperature[to] / resistance[to]);
    }

    float heatRatio() {
        return (float) maximumRatio;
    }

    boolean hotWindwardSurface() {
        return hotWindwardSurface;
    }
}

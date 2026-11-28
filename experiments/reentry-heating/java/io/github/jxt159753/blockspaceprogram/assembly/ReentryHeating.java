package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.assembly.AeroProjection.Side;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import java.util.Arrays;
import java.util.List;
import org.joml.Vector3dc;

/** Transient temperatures on the flight state, using the same six faces as drag. */
final class ReentryHeating {
    static final boolean ENABLED = !Boolean.getBoolean("bsp.reentryHeating.baseline");
    private static final boolean MEASURE = "trajectories".equals(System.getProperty("bsp.reentryHeating.trial"));

    private final ShipDesign design;
    private final AeroProjection dragProjection;
    private final double[] dryMass;
    private final double[] resistance;
    private final double[] temperature;
    private final long hullNodes;
    private AeroProjection heatProjection;
    private long burnedHull;
    private double maximumRatio;
    private final Sample[] samples;
    private double elapsed;

    ReentryHeating(ShipDesign design, AeroProjection projection, long damage) {
        this.design = design;
        dragProjection = projection;
        heatProjection = projection;
        int count = design.nodes().size();
        dryMass = new double[count];
        resistance = new double[count];
        temperature = new double[count];
        Arrays.fill(temperature, ReentryTemperature.AMBIENT_KELVIN);
        samples = MEASURE ? new Sample[count] : null;
        long hull = 0;
        for (int i = 0; i < count; i++) {
            var node = design.nodes().get(i);
            dryMass[i] = node.dryMass();
            if (node.hull() != null) {
                hull |= 1L << i;
            }
            resistance[i] = resistance(node);
            if (samples != null) {
                samples[i] = new Sample();
            }
        }
        hullNodes = hull;
        updateProjection(damage);
    }

    private static double resistance(ShipDesign.Node node) {
        double heat = node.part() == null ? 0 : node.part().properties().heatResistance();
        if (node.hull() != null) {
            double[] min = {Double.POSITIVE_INFINITY};
            node.hull().forEach((pos, material) -> {
                double limit = BlockPartProperties.get(material.state()).heatResistance();
                if (limit > 0) {
                    min[0] = Math.min(min[0], limit);
                }
            });
            heat = Double.isFinite(min[0]) ? min[0] : 0;
        }
        return heat > 0 ? heat : ShipServerConfig.DEFAULT_HEAT_RESISTANCE.get();
    }

    void updateProjection(long damage) {
        long burned = damage & hullNodes;
        if (burnedHull != burned) {
            heatProjection = burned == 0 ? dragProjection : ShipAerodynamics.project(design, burned);
            burnedHull = burned;
        }
    }

    long step(Vector3dc windward, double density, double speed, double multiplier, double seconds,
            double[] fuel, long alreadyDamaged) {
        double equilibrium = ReentryTemperature.equilibrium(speed, multiplier);
        double flow = density * speed;
        long damage = 0;
        double ratio = 0;
        for (int node = 0; node < temperature.length; node++) {
            double area = windward == null ? 0 : heatProjection.exposedArea(windward, node);
            double nodeFlow = flow * area;
            double next = ReentryTemperature.step(temperature[node], equilibrium, nodeFlow, dryMass[node] + fuel[node], seconds);
            temperature[node] = next;
            ratio = Math.max(ratio, next / resistance[node]);
            if (next > resistance[node]) {
                damage |= 1L << node;
            }
            if (samples != null) {
                samples[node].observe(next, resistance[node], flow > 0 ? area : 0, elapsed, seconds);
            }
        }
        maximumRatio = ratio;
        elapsed += seconds;
        return damage & ~alreadyDamaged;
    }

    float heatRatio() {
        return (float) maximumRatio;
    }

    List<Sample> samples() {
        return Arrays.asList(samples);
    }

    double exposedArea(Side side, int node) {
        return heatProjection.exposedArea(side, node);
    }

    void copyTemperatures(ReentryHeating previous, List<Integer> members) {
        for (int i = 0; i < members.size(); i++) {
            temperature[i] = previous.temperature[members.get(i)];
        }
    }

    void copyTemperatures(ReentryHeating previous, int offset) {
        System.arraycopy(previous.temperature, 0, temperature, offset, previous.temperature.length);
    }

    static final class Sample {
        double peak = ReentryTemperature.AMBIENT_KELVIN;
        double overheatSeconds;
        double firstOverheat = -1;
        double firstExposure = -1;

        void observe(double temperature, double limit, double area, double elapsed, double seconds) {
            peak = Math.max(peak, temperature);
            if (temperature > limit) {
                if (firstOverheat < 0) {
                    firstOverheat = elapsed + seconds;
                }
                overheatSeconds += seconds;
            }
            if (area > 1e-6 && firstExposure < 0) {
                firstExposure = elapsed + seconds;
            }
        }
    }
}

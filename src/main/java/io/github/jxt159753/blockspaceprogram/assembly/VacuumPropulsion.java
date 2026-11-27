package io.github.jxt159753.blockspaceprogram.assembly;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** Wet mass, total fuel and the independently fed engine groups of one active stage. */
public record VacuumPropulsion(double wetMass, double fuel, List<Group> groups) {
    static final double STANDARD_GRAVITY = 9.80665;
    public static final StreamCodec<ByteBuf, VacuumPropulsion> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, VacuumPropulsion::wetMass,
            ByteBufCodecs.DOUBLE, VacuumPropulsion::fuel,
            Group.STREAM_CODEC.apply(ByteBufCodecs.list(ShipDesign.MAX_PARTS)), VacuumPropulsion::groups,
            VacuumPropulsion::new);

    public VacuumPropulsion {
        groups = List.copyOf(groups);
    }

    static VacuumPropulsion fromGroups(double wetMass, double fuel, double[] capacity, double[] thrust, double[] flow) {
        var groups = new ArrayList<Group>();
        for (int i = 0; i < capacity.length; i++) {
            if (capacity[i] > 0 && flow[i] > 0 && Double.isFinite(capacity[i] / flow[i])) {
                groups.add(new Group(capacity[i], thrust[i], flow[i]));
            }
        }
        return new VacuumPropulsion(wetMass, fuel, groups);
    }

    double thrustToWeight() {
        return wetMass > 0 ? groups.stream().mapToDouble(Group::thrust).sum() / (wetMass * STANDARD_GRAVITY) : 0;
    }

    /** Integrate the rocket equation piecewise as fuel groups run dry. */
    public Burn estimate(double requestedDeltaV) {
        double delivered = 0;
        double elapsed = 0;
        double consumed = 0;
        while (delivered < requestedDeltaV) {
            double mass = wetMass - consumed;
            double end = Double.POSITIVE_INFINITY;
            double thrust = 0;
            double flow = 0;
            for (Group group : groups) {
                double depletion = group.fuel() / group.flow();
                if (depletion > elapsed) {
                    end = Math.min(end, depletion);
                    thrust += group.thrust();
                    flow += group.flow();
                }
            }
            if (!Double.isFinite(end) || !Double.isFinite(flow) || !(mass > 0) || !Double.isFinite(mass)) {
                break;
            }
            double exhaust = thrust / flow;
            double used = (end - elapsed) * flow;
            double deltaV = exhaust * Math.log1p(used / Math.max(0, mass - used));
            double required = requestedDeltaV - delivered;
            if (Double.isFinite(requestedDeltaV) && required <= deltaV) {
                double amount = Math.min(used, -mass * Math.expm1(-required / exhaust));
                return new Burn(requestedDeltaV, elapsed + amount / flow, consumed + amount);
            }
            delivered += deltaV;
            elapsed = end;
            consumed += used;
        }
        return new Burn(delivered, elapsed, consumed);
    }

    static double fractionRemaining(double fuel, double flow, double seconds) {
        return seconds >= fuel / flow ? 0 : 1 - seconds * flow / fuel;
    }

    public record Burn(double deltaV, double seconds, double fuel) {
        public boolean supplies(double requested) {
            return deltaV >= requested && seconds > 0 && Double.isFinite(seconds);
        }
    }

    public record Group(double fuel, double thrust, double flow) {
        private static final StreamCodec<ByteBuf, Group> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, Group::fuel, ByteBufCodecs.DOUBLE, Group::thrust,
                ByteBufCodecs.DOUBLE, Group::flow, Group::new);
    }
}

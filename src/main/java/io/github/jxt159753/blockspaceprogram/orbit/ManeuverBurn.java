package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion.BurnSimulation;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData.ManeuverResult;
import org.jetbrains.annotations.Nullable;

/** Advances the same thrust segments whether the orbital clock steps once or in a batch. */
final class ManeuverBurn {
    private ManeuverBurn() {}

    @Nullable
    static Outcome advance(OrbitalSystem system, OrbitingShip ship, ManeuverNode node, @Nullable BurnSimulation burn,
            long fromTick, long toTick, Coast coast) {
        int ticksPerSecond = OrbitalSavedData.TICKS_PER_SECOND;
        double from = fromTick / (double) ticksPerSecond;
        double to = toTick / (double) ticksPerSecond;
        double cursor = Math.max(from, node.seconds());
        var progress = ship.maneuverProgress().orElse(new Progress(cursor, 0));
        Orbit orbit = coast.advance(ship.orbit(), from, cursor);
        if (orbit == null) {
            return null;
        }

        double requested = node.deltaV().length();
        double delivered = progress.deltaV();
        ManeuverResult failure = burn == null ? ManeuverResult.INSUFFICIENT_FUEL : null;
        if (burn != null) {
            burnTicks:
            for (long tick = Math.max(fromTick, (long) (cursor * ticksPerSecond)); tick < toTick; tick++) {
                double end = (tick + 1) / (double) ticksPerSecond;
                for (double start = cursor; start < end; ) {
                    double remaining = requested - delivered;
                    if (remaining <= 0) {
                        break burnTicks;
                    }
                    var segment = burn.nextSegment(remaining, end - start);
                    if (segment.seconds() == 0) {
                        failure = starvation(burn);
                        break burnTicks;
                    }
                    double impulseTime = start + segment.seconds() / 2;
                    orbit = coast.advance(orbit, cursor, impulseTime);
                    if (orbit == null) {
                        return null;
                    }
                    cursor = impulseTime;
                    if (segment.deltaV() > 0) {
                        try {
                            orbit = node.apply(system, orbit, impulseTime, segment.deltaV() / requested);
                        } catch (IllegalArgumentException exception) {
                            failure = ManeuverResult.INVALID_NODE;
                            break burnTicks;
                        }
                    }
                    burn.consume(segment);
                    delivered = segment.deltaV() >= remaining ? requested : delivered + segment.deltaV();
                    start += segment.seconds();
                }
                orbit = coast.advance(orbit, cursor, end);
                if (orbit == null) {
                    return null;
                }
                cursor = end;
            }
            // A tank can run dry exactly at the end of the last tick in this batch.
            if (failure == null && delivered < requested && burn.nextSegment(requested - delivered, 1).seconds() == 0) {
                failure = starvation(burn);
            }
        }
        boolean finished = failure != null || delivered >= requested;
        // Settling many partial segments can leave roundoff at the estimated propellant limit.
        if (delivered > 0 && requested - delivered <= 1e-9
                && (failure == ManeuverResult.INSUFFICIENT_FUEL || failure == ManeuverResult.INSUFFICIENT_ELECTRICITY)) {
            failure = null;
        }
        if (cursor < to) {
            orbit = coast.advance(orbit, cursor, to);
        }
        return orbit == null ? null : new Outcome(orbit, new Progress(progress.startedAt(), delivered), finished, failure);
    }

    private static ManeuverResult starvation(BurnSimulation burn) {
        return burn.outOfElectricity() ? ManeuverResult.INSUFFICIENT_ELECTRICITY : ManeuverResult.INSUFFICIENT_FUEL;
    }

    @FunctionalInterface
    interface Coast {
        /** Returns the final conic or null when a warp limit stops the forecast here. */
        @Nullable Orbit advance(Orbit orbit, double from, double to);
    }

    record Progress(double startedAt, double deltaV) {
        private static final Codec<Double> NONNEGATIVE = Codec.doubleRange(0, Double.MAX_VALUE);
        static final Codec<Progress> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                NONNEGATIVE.fieldOf("started_at").forGetter(Progress::startedAt),
                NONNEGATIVE.fieldOf("delta_v").forGetter(Progress::deltaV)
        ).apply(instance, Progress::new));
    }

    record Outcome(Orbit orbit, Progress progress, boolean finished, @Nullable ManeuverResult failure) {}
}

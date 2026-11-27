package io.github.jxt159753.blockspaceprogram.client;

import io.github.jxt159753.blockspaceprogram.orbit.CelestialBody;
import io.github.jxt159753.blockspaceprogram.orbit.ManeuverNode;
import io.github.jxt159753.blockspaceprogram.orbit.Orbit;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalPlanner;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalShipView;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalState;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

record OrbitalMapForecast(Path coast, @Nullable Path preview, @Nullable Vec3 nodePosition,
                          @Nullable OrbitalPlanner.Approach approach, boolean surfaceContact,
                          @Nullable OrbitalPlanner.TransferWindow window) {
    private static final OrbitalSystem SYSTEM = OrbitalSystem.M3;

    static OrbitalMapForecast calculate(Orbit orbit, @Nullable ManeuverNode node, @Nullable CelestialBody targetBody,
                                       @Nullable OrbitalShipView targetShip, double now) {
        double horizon = now + OrbitalPlanner.PREDICTION_SECONDS;
        double period = orbit.elements().period(SYSTEM.body(orbit.center()).gravitationalParameter());
        double coastSpan = Double.isFinite(period) ? Math.min(period, ManeuverNode.MAX_LEAD_SECONDS)
                : OrbitalPlanner.PREDICTION_SECONDS;
        double coastEnd = now + coastSpan;
        var coasting = SYSTEM.forecast(orbit, now, Math.max(Math.max(horizon, coastEnd), node == null ? now : node.seconds()));
        var coast = Path.sample(coasting, now, coastEnd);
        DoubleFunction<OrbitalState> planned = coasting;
        double start = now;
        boolean hasPreview = false;
        Vec3 position = null;
        if (node != null && node.seconds() >= now) {
            position = coasting.apply(node.seconds()).position();
            try {
                Orbit changed = node.preview(SYSTEM, orbit, now);
                planned = SYSTEM.forecast(changed, node.seconds(), node.seconds() + OrbitalPlanner.PREDICTION_SECONDS);
                start = node.seconds();
                horizon = start + OrbitalPlanner.PREDICTION_SECONDS;
                hasPreview = true;
            } catch (IllegalArgumentException ignored) {
                // A drag can momentarily cancel all tangential velocity, which has no Keplerian conic.
            }
        }
        var window = transferWindow(orbit, targetBody, targetShip, now);
        OrbitalPlanner.Approach approach = null;
        boolean surfaceContact = false;
        if (targetBody != null || targetShip != null) {
            DoubleFunction<OrbitalState> target = targetBody != null ? t -> SYSTEM.bodyState(targetBody.id(), t)
                    : targetPath(targetShip, now, horizon);
            approach = OrbitalPlanner.closestApproach(planned, target, start, horizon);
            if (targetBody != null && approach.distance() <= targetBody.radius()) {
                surfaceContact = true;
                approach = OrbitalPlanner.surfaceContact(planned, target, start, approach, targetBody.radius());
            }
        }
        var preview = hasPreview ? Path.sample(planned, start, surfaceContact ? approach.seconds() : horizon) : null;
        return new OrbitalMapForecast(coast, preview, position, approach, surfaceContact, window);
    }

    private static DoubleFunction<OrbitalState> targetPath(OrbitalShipView ship, double now, double end) {
        var node = ship.maneuver().orElse(null);
        if (node != null && node.seconds() > now && node.seconds() < end) {
            var before = SYSTEM.forecast(ship.orbit(), now, node.seconds());
            var after = SYSTEM.forecast(node.preview(SYSTEM, ship.orbit(), now), node.seconds(), end);
            return seconds -> seconds < node.seconds() ? before.apply(seconds) : after.apply(seconds);
        }
        return SYSTEM.forecast(ship.orbit(), now, end);
    }

    @Nullable
    private static OrbitalPlanner.TransferWindow transferWindow(Orbit orbit, @Nullable CelestialBody body,
                                                                 @Nullable OrbitalShipView target, double now) {
        Orbit destination = body != null ? body.orbit() : target == null ? null : target.orbit();
        return destination == null ? null : OrbitalPlanner.hohmann(SYSTEM, orbit, destination, now);
    }

    record Path(List<Vec3> positions, double from, double step) {
        private static final int SEGMENTS = 192;

        static Path sample(DoubleFunction<OrbitalState> source, double from, double to) {
            double step = (to - from) / SEGMENTS;
            var positions = new ArrayList<Vec3>(SEGMENTS + 1);
            for (int i = 0; i <= SEGMENTS; i++) {
                positions.add(source.apply(from + step * i).position());
            }
            return new Path(positions, from, step);
        }

        double pick(OrbitalMapViewport.Frame frame, double x, double y, double earliest) {
            double nearest = 36;
            double time = Double.NaN;
            var previous = frame.project(positions.getFirst());
            for (int i = 1; i < positions.size(); i++) {
                var point = frame.project(positions.get(i));
                double dx = point.x() - previous.x();
                double dy = point.y() - previous.y();
                double length = dx * dx + dy * dy;
                if (length > 0) {
                    double fraction = Math.clamp(((x - previous.x()) * dx + (y - previous.y()) * dy) / length, 0, 1);
                    double errX = previous.x() + dx * fraction - x;
                    double errY = previous.y() + dy * fraction - y;
                    double distance = errX * errX + errY * errY;
                    double seconds = from + step * (i - 1 + fraction);
                    if (seconds >= earliest && distance < nearest) {
                        nearest = distance;
                        time = seconds;
                    }
                }
                previous = point;
            }
            return time;
        }
    }
}

package io.github.jxt159753.blockspaceprogram.webpreview;

import io.github.jxt159753.blockspaceprogram.orbit.OrbitElements;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/** Publishes immutable values. Slow or reconnecting readers do not retain a queue on the server. */
final class WebPreviewFeed {
    private final List<Body> bodies;
    private Frame latest;
    private Clock clock;

    WebPreviewFeed(List<Body> bodies, Map<UUID, Ship> ships, Clock clock) {
        this.bodies = List.copyOf(bodies);
        this.clock = clock;
        latest = new Frame(clock, Map.copyOf(ships));
    }

    synchronized void publish(Clock nextClock, @Nullable Map<UUID, Ship> nextShips) {
        clock = nextClock;
        if (nextShips != null || nextClock.rate() != latest.clock().rate()) {
            latest = new Frame(nextClock, nextShips == null ? latest.ships() : nextShips);
            notifyAll();
        }
    }

    synchronized Start connect() {
        return new Start(clock, bodies, latest);
    }

    synchronized Frame awaitUpdate(Frame previous) throws InterruptedException {
        if (latest == previous) {
            wait(15_000);
        }
        return latest;
    }

    record Clock(double seconds, double rate) {}

    record Body(String id, double radius, double mu, @Nullable Double soi, @Nullable String center,
                @Nullable OrbitElements orbit) {}

    record Ship(String center, @Nullable String name, @Nullable OrbitElements orbit, @Nullable Position position,
                @Nullable Maneuver maneuver) {}

    record Position(double x, double y, double z, double altitude) {}

    record Maneuver(double seconds, double prograde, double normal, double radial) {}

    record Start(Clock clock, List<Body> bodies, Frame frame) {}

    record Full(Clock clock, List<Body> bodies, Map<UUID, Ship> ships) {}

    record Frame(Clock clock, Map<UUID, Ship> ships) {
        Delta since(Frame previous) {
            var updated = new HashMap<UUID, Ship>();
            var removed = new ArrayList<UUID>();
            ships.forEach((id, ship) -> {
                if (!ship.equals(previous.ships().get(id))) {
                    updated.put(id, ship);
                }
            });
            for (UUID id : previous.ships().keySet()) {
                if (!ships.containsKey(id)) {
                    removed.add(id);
                }
            }
            return new Delta(clock, updated, removed);
        }
    }

    record Delta(Clock clock, Map<UUID, Ship> updated, List<UUID> removed) {}
}

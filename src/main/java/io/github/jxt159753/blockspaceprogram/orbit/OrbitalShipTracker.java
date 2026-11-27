package io.github.jxt159753.blockspaceprogram.orbit;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Ship conics currently held by one client. */
final class OrbitalShipTracker {
    private Set<UUID> visible = new HashSet<>();
    @Nullable private Interest previous;

    void sync(OrbitalSavedData data, Interest interest, Set<UUID> changes, BiConsumer<UUID, OrbitingShip> send) {
        if (!interest.equals(previous)) {
            var next = new HashSet<UUID>();
            for (OrbitingShip ship : data.ships()) {
                if (interest.includes(ship)) {
                    var id = ship.id();
                    next.add(id);
                    if (!visible.contains(id) || changes.contains(id)) {
                        send.accept(id, ship);
                    }
                }
            }
            visible.removeAll(next);
            visible.forEach(id -> send.accept(id, null));
            visible = next;
            previous = interest;
            return;
        }
        for (UUID id : changes) {
            var ship = data.ship(id);
            if (ship != null && interest.includes(ship)) {
                visible.add(id);
                send.accept(id, ship);
            } else if (visible.remove(id)) {
                send.accept(id, null);
            }
        }
    }

    record Interest(UUID player, ResourceLocation center, boolean showAll) {
        boolean includes(OrbitingShip ship) {
            return showAll || ship.orbit().center().equals(center) || player.equals(ship.ownerId());
        }
    }
}

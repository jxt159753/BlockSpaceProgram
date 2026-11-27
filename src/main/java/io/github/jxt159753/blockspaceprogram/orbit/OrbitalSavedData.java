package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import io.github.jxt159753.blockspaceprogram.assembly.OrbitalShipPropulsion;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/** One orbit clock and ship store for all dimensions. Access it on the server thread. */
public final class OrbitalSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final OrbitalSystem SYSTEM = OrbitalSystem.M3;
    public static final int TICKS_PER_SECOND = 20;
    static final String STORAGE_ID = "blockspaceprogram_orbits";
    static final Factory<OrbitalSavedData> FACTORY = new Factory<>(OrbitalSavedData::new, OrbitalSavedData::load);
    private static final Codec<List<OrbitingShip>> SHIPS_CODEC = OrbitingShip.CODEC.listOf();

    private final Map<UUID, OrbitingShip> ships = new HashMap<>();
    private final Set<UUID> activeShips = new HashSet<>();
    private final Set<UUID> changes = new HashSet<>();
    private long orbitTicks;

    private OrbitalSavedData() {}

    public static OrbitalSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, STORAGE_ID);
    }

    public long orbitTicks() {
        return orbitTicks;
    }

    public double orbitSeconds() {
        return orbitTicks / (double) TICKS_PER_SECOND;
    }

    public Collection<OrbitingShip> ships() {
        return Collections.unmodifiableCollection(ships.values());
    }

    @Nullable
    public OrbitingShip ship(UUID id) {
        return ships.get(id);
    }

    public void put(OrbitingShip ship) {
        index(ship);
        changes.add(ship.id());
        setDirty();
    }

    @Nullable
    public OrbitingShip remove(UUID id) {
        OrbitingShip removed = ships.remove(id);
        if (removed != null) {
            activeShips.remove(id);
            changes.add(id);
            setDirty();
        }
        return removed;
    }

    ManeuverResult commandManeuver(UUID id, Optional<ManeuverNode> command, HolderLookup.Provider registries) {
        OrbitingShip ship = ships.get(id);
        if (ship == null) {
            return ManeuverResult.MISSING_SHIP;
        }
        if (command.isEmpty()) {
            if (ship.maneuver().isEmpty()) {
                return ManeuverResult.NO_NODE;
            }
            put(ship.withManeuver(Optional.empty()));
            return ManeuverResult.CANCELED;
        }
        if (ship.maneuver().isPresent()) {
            return ManeuverResult.ALREADY_QUEUED;
        }
        ManeuverNode node = command.get();
        double now = orbitSeconds();
        if (!node.valid() || node.seconds() <= now || node.seconds() > now + ManeuverNode.MAX_LEAD_SECONDS
                || node.deltaV().lengthSqr() == 0) {
            return ManeuverResult.INVALID_NODE;
        }
        var snapshot = OrbitalShipPropulsion.read(registries, ship.shipData());
        double deltaV = node.deltaV().length();
        if (snapshot == null || !snapshot.propulsion().estimate(deltaV).supplies(deltaV)) {
            return ManeuverResult.INSUFFICIENT_FUEL;
        }
        try {
            node.preview(SYSTEM, ship.orbit(), now);
        } catch (IllegalArgumentException exception) {
            return ManeuverResult.INVALID_NODE;
        }
        put(ship.withManeuver(command));
        return ManeuverResult.QUEUED;
    }

    double nextManeuverSeconds() {
        double next = Double.POSITIVE_INFINITY;
        for (UUID id : activeShips) {
            var node = ships.get(id).maneuver().orElse(null);
            if (node != null) {
                next = Math.min(next, node.seconds());
            }
        }
        return next;
    }

    void advanceTicks(HolderLookup.Provider registries, int ticks) {
        double from = orbitSeconds();
        orbitTicks += ticks;
        double to = orbitSeconds();
        var candidates = activeShips.iterator();
        while (candidates.hasNext()) {
            UUID id = candidates.next();
            OrbitingShip ship = ships.get(id);
            ManeuverNode node = ship.maneuver().orElse(null);
            OrbitingShip advanced = ship;
            double start = from;
            if (node != null && node.seconds() <= to) {
                // Fit the impulse at its exact epoch, even when it falls between ticks.
                start = Math.max(from, node.seconds());
                Orbit before = SYSTEM.advance(ship.orbit(), from, start);
                advanced = ignite(ship.withOrbit(before), node, start, registries);
            }
            Orbit next = SYSTEM.advance(advanced.orbit(), start, to);
            if (next != advanced.orbit()) {
                advanced = advanced.withOrbit(next);
            }
            if (advanced != ship) {
                ships.put(id, advanced);
                changes.add(id);
                if (!needsAdvancing(advanced)) {
                    candidates.remove();
                }
            }
        }
        setDirty();
    }

    private static OrbitingShip ignite(OrbitingShip ship, ManeuverNode node, double seconds,
                                      HolderLookup.Provider registries) {
        var snapshot = OrbitalShipPropulsion.read(registries, ship.shipData());
        double deltaV = node.deltaV().length();
        if (snapshot != null) {
            var burn = snapshot.propulsion().estimate(deltaV);
            if (burn.supplies(deltaV)) {
                try {
                    Orbit next = new ManeuverNode(seconds, node.deltaV()).apply(SYSTEM, ship.orbit());
                    return new OrbitingShip(ship.id(), next, ship.orientation(), ship.angularVelocity(),
                            snapshot.afterBurn(burn.seconds()));
                } catch (IllegalArgumentException exception) {
                    LOGGER.warn("Could not apply maneuver on orbiting ship {}", ship.id(), exception);
                }
            }
        }
        return ship.withManeuver(Optional.empty());
    }

    Set<UUID> takeChanges() {
        Set<UUID> updated = Set.copyOf(changes);
        changes.clear();
        return updated;
    }

    private void index(OrbitingShip ship) {
        ships.put(ship.id(), ship);
        if (needsAdvancing(ship)) {
            activeShips.add(ship.id());
        } else {
            activeShips.remove(ship.id());
        }
    }

    private static boolean needsAdvancing(OrbitingShip ship) {
        return ship.maneuver().isPresent() || SYSTEM.mayCross(ship.orbit());
    }

    private static OrbitalSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var data = new OrbitalSavedData();
        data.orbitTicks = tag.getLong("orbit_ticks");
        for (OrbitingShip ship : SHIPS_CODEC.parse(NbtOps.INSTANCE, tag.get("ships")).getOrThrow()) {
            data.index(ship);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("orbit_ticks", orbitTicks);
        tag.put("ships", SHIPS_CODEC.encodeStart(NbtOps.INSTANCE, List.copyOf(ships.values())).getOrThrow());
        return tag;
    }

    public enum ManeuverResult {
        QUEUED, CANCELED, FORBIDDEN, MISSING_SHIP, NO_NODE, ALREADY_QUEUED, INVALID_NODE, INSUFFICIENT_FUEL
    }
}

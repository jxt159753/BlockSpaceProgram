package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import io.github.jxt159753.blockspaceprogram.assembly.OrbitalShipPropulsion;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.Propellant;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControls;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/** One star system, orbit clock and ship store for all dimensions. Access it on the server thread. */
public final class OrbitalSavedData extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int TICKS_PER_SECOND = 20;
    static final String STORAGE_ID = "blockspaceprogram_orbits";
    static final Factory<OrbitalSavedData> FACTORY = new Factory<>(
            () -> new OrbitalSavedData(StarSystem.LEGACY), OrbitalSavedData::load);
    private static final Codec<List<OrbitingShip>> SHIPS_CODEC = OrbitingShip.CODEC.listOf();

    private final StarSystem starSystem;
    private final OrbitalSystem system;
    private final Map<UUID, OrbitingShip> ships = new HashMap<>();
    // Drafts and physics setpoints are session state. Only armed burns belong in the save.
    private final Map<UUID, ManeuverNode> maneuverDrafts = new HashMap<>();
    private final Map<UUID, ShipFlightControls> handoffControls = new HashMap<>();
    private final Set<UUID> activeShips = new HashSet<>();
    private final Set<UUID> changes = new HashSet<>();
    private Set<UUID> lastChanges = Set.of();
    private long orbitTicks;
    @Nullable private OrbitalRegion orbitalRegions;

    OrbitalSavedData(StarSystem starSystem) {
        this.starSystem = starSystem;
        this.system = OrbitalSystem.withMoonOrbit(starSystem.moonOrbit());
    }

    public static OrbitalSavedData get(MinecraftServer server) {
        var overworld = server.overworld();
        var storage = overworld.getDataStorage();
        var data = storage.get(FACTORY, STORAGE_ID);
        if (data == null) {
            boolean initialized = server.getWorldData().overworldData().isInitialized();
            data = new OrbitalSavedData(initialized ? StarSystem.LEGACY : StarSystem.forNewWorld(overworld.getSeed()));
            data.setDirty();
            storage.set(STORAGE_ID, data);
        }
        return data;
    }

    public OrbitalSystem system() {
        return system;
    }

    OrbitalRegion orbitalRegions(DimensionDataStorage storage) {
        if (orbitalRegions == null) {
            orbitalRegions = new OrbitalRegion(storage);
        }
        return orbitalRegions;
    }

    StarSystem starSystem() {
        return starSystem;
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
        maneuverDrafts.remove(ship.id());
        changes.add(ship.id());
        setDirty();
    }

    void store(OrbitingShip ship, ShipFlightControls controls) {
        put(ship);
        handoffControls.put(ship.id(), controls);
    }

    @Nullable
    ShipFlightControls handoffControls(UUID id) {
        return handoffControls.get(id);
    }

    @Nullable
    public OrbitingShip remove(UUID id) {
        OrbitingShip removed = ships.remove(id);
        if (removed != null) {
            activeShips.remove(id);
            maneuverDrafts.remove(id);
            handoffControls.remove(id);
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
                return maneuverDrafts.remove(id) == null ? ManeuverResult.NO_NODE : ManeuverResult.CANCELED;
            }
            put(ship.withManeuver(Optional.empty()));
            return ManeuverResult.CANCELED;
        }
        return submitManeuver(ship, command.get(), registries, true);
    }

    ManeuverResult planManeuver(OrbitingShip ship, ManeuverNode node, HolderLookup.Provider registries) {
        return submitManeuver(ship, node, registries, false);
    }

    ManeuverResult executeManeuver(OrbitingShip ship, HolderLookup.Provider registries) {
        if (ship.maneuver().isPresent()) {
            return ManeuverResult.ALREADY_QUEUED;
        }
        var draft = maneuverDrafts.get(ship.id());
        return draft == null ? ManeuverResult.NO_NODE : submitManeuver(ship, draft, registries, true);
    }

    Optional<ManeuverNode> maneuverDraft(UUID id) {
        return Optional.ofNullable(maneuverDrafts.get(id));
    }

    private ManeuverResult submitManeuver(OrbitingShip ship, ManeuverNode node, HolderLookup.Provider registries,
            boolean queue) {
        if (ship.maneuver().isPresent()) {
            return ManeuverResult.ALREADY_QUEUED;
        }
        double now = orbitSeconds();
        if (!node.valid() || node.seconds() <= now || node.seconds() > now + ManeuverNode.MAX_LEAD_SECONDS
                || node.deltaV().lengthSqr() == 0) {
            return ManeuverResult.INVALID_NODE;
        }
        var snapshot = OrbitalShipPropulsion.read(registries, ship.shipData());
        double deltaV = node.deltaV().length();
        if (snapshot == null) {
            return ManeuverResult.INSUFFICIENT_FUEL;
        }
        var propulsion = snapshot.propulsion();
        if (propulsion.thrustCancels()) {
            return ManeuverResult.NO_THRUST;
        }
        if (!propulsion.estimate(deltaV).supplies(deltaV)) {
            return propulsion.electricityLimits(deltaV) ? ManeuverResult.INSUFFICIENT_ELECTRICITY
                    : ManeuverResult.INSUFFICIENT_FUEL;
        }
        try {
            node.preview(system, ship.orbit(), now);
        } catch (IllegalArgumentException exception) {
            return ManeuverResult.INVALID_NODE;
        }
        if (queue) {
            put(ship.withManeuver(Optional.of(node)));
            return ManeuverResult.QUEUED;
        }
        maneuverDrafts.put(ship.id(), node);
        return ManeuverResult.PLANNED;
    }

    AdvanceResult advanceTicks(HolderLookup.Provider registries, int ticks) {
        long fromTick = orbitTicks;
        double from = orbitSeconds();
        orbitTicks += ticks;
        double to = orbitSeconds();
        var crossings = new ArrayList<ShipOrbitEvent>();
        var failures = new ArrayList<ManeuverFailure>();
        var candidates = activeShips.iterator();
        while (candidates.hasNext()) {
            UUID id = candidates.next();
            OrbitingShip ship = ships.get(id);
            ManeuverNode node = ship.maneuver().orElse(null);
            OrbitingShip advanced;
            if (node != null && node.seconds() < to) {
                advanced = advanceManeuver(ship, node, registries, fromTick, crossings, failures);
            } else {
                Orbit next = system.advance(ship.orbit(), from, to, (before, after) ->
                        crossings.add(ShipOrbitEvent.soiChanged(ship, registries, before, after)));
                advanced = next == ship.orbit() ? ship : ship.withOrbit(next);
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
        return new AdvanceResult(crossings, failures);
    }

    private OrbitingShip advanceManeuver(OrbitingShip ship, ManeuverNode node, HolderLookup.Provider registries,
            long fromTick, List<ShipOrbitEvent> crossings, List<ManeuverFailure> failures) {
        var shipData = ship.shipData();
        var snapshot = OrbitalShipPropulsion.read(registries, shipData);
        var burn = snapshot == null ? null : snapshot.propulsion().startBurn();
        BiConsumer<Orbit, Orbit> onCrossing = (before, after) -> {
            var remaining = burn == null ? new Propellant(0, 0) : new Propellant(burn.remainingFuel(), burn.remainingElectric());
            crossings.add(ShipOrbitEvent.soiChanged(ship.id(), ship.ownerId(), before, after, remaining));
        };
        var outcome = ManeuverBurn.advance(system, ship, node, burn, fromTick, orbitTicks,
                (orbit, start, end) -> system.advance(orbit, start, end, onCrossing));
        if (outcome.failure() != null) {
            double delivered = outcome.progress().deltaV();
            LOGGER.info("Orbiting ship {} stopped its burn after delivering {} m/s: {}", ship.id(), delivered, outcome.failure());
            failures.add(new ManeuverFailure(ship.id(), ship.ownerId(), outcome.failure(), delivered));
        }
        if (burn != null) {
            var consumed = burn.result();
            if (consumed.seconds() > 0) {
                shipData = snapshot.afterBurn(consumed);
            }
        }
        return ship.afterBurn(outcome, shipData);
    }

    Set<UUID> takeChanges() {
        lastChanges = Set.copyOf(changes);
        changes.clear();
        return lastChanges;
    }

    /** Changes committed by the orbital tick, for later server tick listeners. */
    public Set<UUID> lastChanges() {
        return lastChanges;
    }

    private void index(OrbitingShip ship) {
        ships.put(ship.id(), ship);
        if (needsAdvancing(ship)) {
            activeShips.add(ship.id());
        } else {
            activeShips.remove(ship.id());
        }
    }

    private boolean needsAdvancing(OrbitingShip ship) {
        return ship.maneuver().isPresent() || system.mayCross(ship.orbit());
    }

    private static OrbitalSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        var storedSystem = tag.get("star_system");
        var starSystem = storedSystem == null ? StarSystem.LEGACY
                : StarSystem.CODEC.parse(NbtOps.INSTANCE, storedSystem).getOrThrow();
        var data = new OrbitalSavedData(starSystem);
        data.orbitTicks = tag.getLong("orbit_ticks");
        for (OrbitingShip ship : SHIPS_CODEC.parse(NbtOps.INSTANCE, tag.get("ships")).getOrThrow()) {
            data.index(ship);
        }
        if (storedSystem == null) {
            data.setDirty();
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("orbit_ticks", orbitTicks);
        tag.put("ships", SHIPS_CODEC.encodeStart(NbtOps.INSTANCE, List.copyOf(ships.values())).getOrThrow());
        tag.put("star_system", StarSystem.CODEC.encodeStart(NbtOps.INSTANCE, starSystem).getOrThrow());
        return tag;
    }

    public enum ManeuverResult {
        QUEUED, CANCELED, FORBIDDEN, MISSING_SHIP, NO_NODE, ALREADY_QUEUED, INVALID_NODE, INSUFFICIENT_FUEL,
        PLANNED, NO_THRUST, INSUFFICIENT_ELECTRICITY
    }

    record AdvanceResult(List<ShipOrbitEvent> crossings, List<ManeuverFailure> failures) {}

    record ManeuverFailure(UUID ship, @Nullable UUID owner, ManeuverResult reason, double deltaV) {}
}

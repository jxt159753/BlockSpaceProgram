package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.serialization.Codec;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

/** One orbit clock and ship store for all dimensions. Access it on the server thread. */
public final class OrbitalSavedData extends SavedData {
    public static final int TICKS_PER_SECOND = 20;
    static final String STORAGE_ID = "blockspaceprogram_orbits";
    static final Factory<OrbitalSavedData> FACTORY = new Factory<>(OrbitalSavedData::new, OrbitalSavedData::load);
    private static final Codec<List<OrbitingShip>> SHIPS_CODEC = OrbitingShip.CODEC.listOf();

    private final Map<UUID, OrbitingShip> ships = new HashMap<>();
    private final Set<UUID> crossingCandidates = new HashSet<>();
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
            crossingCandidates.remove(id);
            changes.add(id);
            setDirty();
        }
        return removed;
    }

    void advanceTick() {
        double from = orbitSeconds();
        orbitTicks++;
        double to = orbitSeconds();
        var candidates = crossingCandidates.iterator();
        while (candidates.hasNext()) {
            UUID id = candidates.next();
            OrbitingShip ship = ships.get(id);
            Orbit next = OrbitalSystem.M3.advance(ship.orbit(), from, to);
            if (next != ship.orbit()) {
                ships.put(id, ship.withOrbit(next));
                changes.add(id);
                if (!OrbitalSystem.M3.mayCross(next)) {
                    candidates.remove();
                }
            }
        }
        setDirty();
    }

    Set<UUID> takeChanges() {
        Set<UUID> updated = Set.copyOf(changes);
        changes.clear();
        return updated;
    }

    private void index(OrbitingShip ship) {
        boolean mayCross = OrbitalSystem.M3.mayCross(ship.orbit());
        ships.put(ship.id(), ship);
        if (mayCross) {
            crossingCandidates.add(ship.id());
        } else {
            crossingCandidates.remove(ship.id());
        }
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
}

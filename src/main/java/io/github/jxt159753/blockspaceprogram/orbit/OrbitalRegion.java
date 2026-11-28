package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight.Motion;
import io.github.jxt159753.blockspaceprogram.assembly.ShipServerConfig;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.DoubleFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.slf4j.Logger;

/** Owns the translating, non-rotating frames in the orbital-space dimension. */
public final class OrbitalRegion {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SavedData.Factory<Region> FACTORY = new SavedData.Factory<>(Region::new, Region::load);
    private static final String STORAGE_ID = "blockspaceprogram_orbital_region";
    private static final String PLAYER_RETURN = "BlockSpaceProgramOrbitalReturn";
    private static final String EVA_FLIGHT = "BlockSpaceProgramEvaFlight";
    private static final ResourceLocation ZERO_GRAVITY =
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "orbital_gravity");
    private static final double REBASE_DISTANCE = 64;
    private static final double REBASE_SPEED = 12;
    private static final double ENCOUNTER_LOOKAHEAD = 1;
    private static final int SLOT_SPACING = 16_384;
    static final int CONTACT_INTERVAL = 5;
    public static final double CONTACT_RANGE = 4_000;
    // Leave clearance below each region for target ships and untethered players.
    public static final Vec3 ORIGIN = new Vec3(0, 4096, 0);

    private final DimensionDataStorage storage;
    private final Map<Vec3, Region> regions = new LinkedHashMap<>();
    private final Map<UUID, Region> assignments = new HashMap<>();
    private final Map<UUID, ShipOrbitHandoff.RegionDescent> pendingDescents = new HashMap<>();
    private final OrbitalTimeWarp timeWarp = new OrbitalTimeWarp();
    private List<Encounter> encounters = List.of();
    @Nullable private BenchmarkOrbit benchmark;
    private final Set<UUID> benchmarkShips = new HashSet<>();

    OrbitalRegion(DimensionDataStorage storage) {
        this.storage = storage;
        // Slots are allocated in order. Recycled slots retain an empty record for reuse.
        for (int slot = 0; ; slot++) {
            var region = storage.get(FACTORY, storageId(slot));
            if (region == null) {
                break;
            }
            addSlot(slot, region);
        }
    }

    static OrbitalRegion get(MinecraftServer server) {
        return OrbitalSavedData.get(server).orbitalRegions(server.overworld().getDataStorage());
    }

    OrbitalTimeWarp timeWarp() {
        return timeWarp;
    }

    @Nullable
    Orbit reference() {
        // Only the private single-player time warp needs a global reference.
        return regions.values().stream().filter(region -> !region.members.isEmpty())
                .findFirst().map(region -> region.reference).orElse(null);
    }

    @Nullable
    Orbit orbitFor(AssembledShipEntity ship, OrbitalSavedData data) {
        var region = regionFor(ship);
        if (region == null || benchmark != null) {
            return null;
        }
        try {
            return fit(data.system(), region.frame(data).absolute(localState(ship)), data.orbitSeconds());
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    List<OrbitalState> warpMembers(ServerLevel space) {
        syncMembers(space);
        return regions.values().stream().filter(region -> !region.members.isEmpty()).findFirst()
                .map(region -> region.members.values().stream().map(OrbitalRegion::localState)
                        .map(state -> new OrbitalState(toInertial(state.position().subtract(region.origin)),
                                toInertial(state.velocity()))).toList()).orElseGet(List::of);
    }

    @Nullable
    public static Orbit referenceFor(ServerPlayer player) {
        var view = viewFor(player);
        return view == null ? null : view.reference();
    }

    @Nullable
    public static View viewFor(ServerPlayer player) {
        if (!inSpace(player) || !ModList.get().isLoaded("sable")) {
            return null;
        }
        var regions = get(player.serverLevel().getServer());
        var benchmark = regions.benchmark;
        if (benchmark != null && player.getUUID().equals(benchmark.observer())) {
            return new View(ORIGIN, benchmark.reference());
        }
        var region = regions.regionFor(player);
        return region == null ? null : new View(region.origin, region.reference);
    }

    public static boolean inSpace(ServerPlayer player) {
        return player.level().dimension().equals(BlockSpaceProgram.ORBITAL_SPACE);
    }

    // The fixed axes match the orbital sky: Minecraft's +Y points along inertial +Z.
    public static Vec3 toInertial(Vec3 local) {
        return new Vec3(local.x, -local.z, local.y);
    }

    public static Vec3 toLocal(Vec3 inertial) {
        return new Vec3(inertial.x, inertial.z, -inertial.y);
    }

    static Quaterniond toInertialRotation() {
        return new Quaterniond().rotationX(Math.PI / 2);
    }

    private static String storageId(int slot) {
        return slot == 0 ? STORAGE_ID : STORAGE_ID + "_" + slot;
    }

    private void addSlot(int slot, Region region) {
        region.origin = slotOrigin(slot);
        regions.put(region.origin, region);
    }

    static Vec3 slotOrigin(int slot) {
        if (slot == 0) {
            return ORIGIN;
        }
        // Fill square rings so the first slots stay close to zero on both axes.
        int ring = ((int) Math.sqrt(slot) + 1) / 2;
        int side = 2 * ring;
        int offset = slot - (side - 1) * (side - 1);
        int along = offset % side - ring;
        return switch (offset / side) {
            case 0 -> ORIGIN.add(along * SLOT_SPACING, 0, -ring * SLOT_SPACING);
            case 1 -> ORIGIN.add(ring * SLOT_SPACING, 0, along * SLOT_SPACING);
            case 2 -> ORIGIN.add(-along * SLOT_SPACING, 0, ring * SLOT_SPACING);
            default -> ORIGIN.add(-ring * SLOT_SPACING, 0, -along * SLOT_SPACING);
        };
    }

    private static Vec3 slotAt(Vec3 position) {
        return new Vec3(SLOT_SPACING * Math.round(position.x / SLOT_SPACING), ORIGIN.y,
                SLOT_SPACING * Math.round(position.z / SLOT_SPACING));
    }

    @Nullable
    private Region allocate(ServerLevel space) {
        for (var region : regions.values()) {
            if (region.reference == null && ShipOrbitHandoff.validPosition(space, region.origin)) {
                return region;
            }
        }
        int slot = regions.size();
        if (!ShipOrbitHandoff.validPosition(space, slotOrigin(slot))) {
            return null;
        }
        var region = new Region();
        addSlot(slot, region);
        storage.set(storageId(slot), region);
        return region;
    }

    boolean availableForBenchmark(ServerLevel space) {
        return benchmark == null && regions.values().stream().noneMatch(region -> region.reference != null)
                && space.players().isEmpty() && SableShipFlight.activeShips(space).isEmpty();
    }

    boolean enterBenchmark(ServerPlayer player, ServerLevel space, Vec3 position, float yaw, float pitch) {
        var data = OrbitalSavedData.get(space.getServer());
        var body = data.system().body(Level.OVERWORLD.location());
        double epoch = data.orbitSeconds();
        var orbit = new Orbit(body.id(), new OrbitElements(body.radius() + 10_000, 0, 0, 0, 0, 0, epoch));
        timeWarp.reset(space);
        if (!SableShipFlight.movePlayer(player, space, position, Vec3.ZERO, yaw, pitch)) {
            return false;
        }
        benchmark = new BenchmarkOrbit(orbit, player.getUUID());
        return true;
    }

    boolean benchmarking() {
        return benchmark != null;
    }

    void addBenchmarkShip(AssembledShipEntity ship) {
        benchmarkShips.add(ship.getUUID());
    }

    boolean hasBenchmarkShip(AssembledShipEntity ship) {
        return benchmark != null && benchmarkShips.contains(ship.getUUID());
    }

    void endBenchmark() {
        benchmark = null;
        benchmarkShips.clear();
    }

    boolean enter(OrbitalSavedData data, AssembledShipEntity ship, Orbit orbit, Motion motion, Quaterniondc rotation) {
        var space = ship.getServer().getLevel(BlockSpaceProgram.ORBITAL_SPACE);
        if (space == null) {
            return false;
        }
        BenchmarkCommands.interruptOrbital(space);
        var system = data.system();
        double now = data.orbitSeconds();
        var absolute = system.absoluteState(orbit, now);
        var region = entryRegion(space, data, absolute);
        if (region == null) {
            return false;
        }
        var frame = region.entryFrame(data, orbit);
        var transform = toInertialRotation().conjugate().mul(rotation);
        var angular = rotation.transform(new Vector3d(motion.angularVelocity().x, motion.angularVelocity().y,
                motion.angularVelocity().z));
        var local = frame.localMotion(absolute, new Quaterniond(rotation).mul(motion.orientation()),
                new Vec3(angular.x, angular.y, angular.z));
        var restored = ship.activeFlight().transfer(space, local, transform, false);
        if (restored == null) {
            return false;
        }
        registerEntry(region, restored, orbit, frame);
        return true;
    }

    @Nullable
    private Region entryRegion(ServerLevel space, OrbitalSavedData data, OrbitalState state) {
        var occupied = occupiedRegions(space);
        if (OrbitalTimeWarp.isPrivateWorld(space.getServer()) && !occupied.isEmpty()) {
            return occupied.iterator().next();
        }
        var nearby = nearestRegion(snapshots(occupied, data), state);
        return nearby == null ? allocate(space) : nearby.region();
    }

    private void registerEntry(Region region, Entity entity, Orbit orbit, Frame frame) {
        if (region.reference == null) {
            region.setReference(orbit, entity.getUUID());
        }
        track(region, entity, true);
        if (entity instanceof AssembledShipEntity ship) {
            frame.configurePhysics(ship);
        }
    }

    void tick(ServerLevel space, OrbitalSavedData data, double previousSeconds, boolean warped) {
        if (benchmark != null) {
            return;
        }
        syncMembers(space);
        var occupied = occupiedRegions(space);
        recycleUnoccupied(data, occupied);
        boolean checkContacts = data.orbitTicks() % CONTACT_INTERVAL == 0;
        boolean transferred = false;
        for (var region : occupied) {
            advanceReference(region, data, previousSeconds);
            if (!warped) {
                transferred |= descend(space, data, region, checkContacts);
            }
        }
        if (transferred) {
            syncMembers(space);
            occupied = occupiedRegions(space);
            recycleUnoccupied(data, occupied);
        }

        if (!warped) {
            if (!OrbitalTimeWarp.isPrivateWorld(space.getServer())) {
                if (checkContacts) {
                    split(space, data, occupied);
                    occupied = occupiedRegions(space);
                    encounters = forecastEncounters(data, occupied);
                }
                if (merge(space, data, occupied)) {
                    occupied = occupiedRegions(space);
                }
            }
            if (checkContacts) {
                exchangeShips(space, data, occupied);
            }
        }
        for (var region : occupied) {
            if (!warped) {
                rebase(space, data, region);
            }
            var frame = region.frame(data);
            for (var member : region.members.values()) {
                if (member instanceof AssembledShipEntity ship) {
                    frame.configurePhysics(ship);
                }
            }
        }
    }

    private void advanceReference(Region region, OrbitalSavedData data, double previousSeconds) {
        var legs = data.system().conics(region.reference, previousSeconds, data.orbitSeconds());
        if (legs.size() > 1) {
            region.setReference(legs.getLast(), region.anchor);
            for (int index = 1; index < legs.size(); index++) {
                postSoiChange(region, legs.get(index - 1), legs.get(index));
            }
        }
    }

    private boolean descend(ServerLevel space, OrbitalSavedData data, Region region, boolean forecast) {
        var frame = region.frame(data);
        boolean transferred = false;
        for (Entity member : List.copyOf(region.members.values())) {
            if (member instanceof AssembledShipEntity ship) {
                try {
                    var id = ship.getUUID();
                    var descent = pendingDescents.get(id);
                    if (descent == null) {
                        descent = ShipOrbitHandoff.regionDescent(space.getServer(), data.system(),
                                frame.absolute(localState(ship)), frame.seconds(), forecast);
                    }
                    if (descent == null) {
                        continue;
                    }
                    if (!descent.ready()) {
                        // Keep the first crossing's position and velocity while the terrain finishes loading.
                        pendingDescents.put(id, descent);
                        continue;
                    }
                    pendingDescents.remove(id);
                    var propellant = ship.propellant();
                    if (descent.transfer(ship)) {
                        region.members.remove(id);
                        assignments.remove(id);
                        transferred = true;
                        NeoForge.EVENT_BUS.post(ShipOrbitEvent.leftOrbit(id, ship.owner(),
                                descent.level().dimension().location(), region.reference, propellant));
                    }
                } catch (RuntimeException error) {
                    LOGGER.warn("Could not return ship {} from orbital space", ship.getUUID(), error);
                }
            }
        }
        return transferred;
    }

    @Nullable
    private Region regionFor(Entity entity) {
        if (entity instanceof ServerPlayer player) {
            var ship = SableShipFlight.carriedShip(player);
            if (ship != null) {
                return regionFor(ship);
            }
        }
        var assigned = assignments.get(entity.getUUID());
        var region = assigned == null ? regions.get(slotAt(localState(entity).position())) : assigned;
        return region != null && region.reference != null ? region : null;
    }

    private void track(Region region, Entity entity, boolean independent) {
        var id = entity.getUUID();
        var previous = assignments.put(id, region);
        if (previous != null && previous != region) {
            previous.members.remove(id);
        }
        if (independent) {
            region.members.put(id, entity);
        } else {
            region.members.remove(id);
        }
        region.seen = true;
    }

    private void syncMembers(ServerLevel space) {
        var active = new HashSet<UUID>();
        var independent = new HashSet<UUID>();
        for (var ship : SableShipFlight.activeShips(space)) {
            var region = regionFor(ship);
            if (region != null) {
                track(region, ship, true);
                active.add(ship.getUUID());
                independent.add(ship.getUUID());
            }
        }
        for (var player : space.players()) {
            if (!player.isAlive() || player.getPersistentData().contains(PLAYER_RETURN)) {
                continue;
            }
            var region = regionFor(player);
            if (region != null) {
                boolean eva = SableShipFlight.carriedShip(player) == null;
                track(region, player, eva);
                active.add(player.getUUID());
                if (eva) {
                    independent.add(player.getUUID());
                }
            }
        }
        assignments.keySet().retainAll(active);
        regions.values().forEach(region -> region.members.keySet().retainAll(independent));
        pendingDescents.keySet().retainAll(independent);
    }

    private Set<Region> occupiedRegions(ServerLevel space) {
        var occupied = new LinkedHashSet<Region>();
        for (var player : space.players()) {
            if (player.isAlive() && !player.getPersistentData().contains(PLAYER_RETURN)) {
                var region = regionFor(player);
                if (region != null) {
                    occupied.add(region);
                }
            }
        }
        return occupied;
    }


    private void split(ServerLevel space, OrbitalSavedData data, Set<Region> occupied) {
        double exit = ShipServerConfig.orbitExitDistance();
        for (var region : occupied) {
            var crewed = region.members.values().stream().filter(OrbitalRegion::hasPlayer).toList();
            if (crewed.size() < 2) {
                continue;
            }
            var sample = region.snapshot(data);
            var isolated = crewed.stream().filter(member -> sample.isolated(member.getUUID(), exit)).toList();
            // When every crewed member is isolated, keep one in the existing slot.
            var keep = isolated.size() == crewed.size()
                    ? crewed.stream().filter(member -> member.getUUID().equals(region.anchor)).findFirst().orElse(crewed.getFirst())
                    : null;
            for (var member : isolated) {
                if (member == keep) {
                    continue;
                }
                var state = sample.states().get(member.getUUID());
                Orbit orbit;
                try {
                    orbit = fit(data.system(), state, data.orbitSeconds());
                } catch (IllegalArgumentException error) {
                    // A radial trajectory cannot serve as a Kepler reference.
                    continue;
                }
                var target = allocate(space);
                if (target == null) {
                    break;
                }
                var frame = target.entryFrame(data, orbit);
                if (shift(space, region, List.of(member), false, sample.frame().shiftTo(frame))) {
                    registerEntry(target, member, orbit, frame);
                }
            }
        }
    }

    private List<Encounter> forecastEncounters(OrbitalSavedData data, Set<Region> occupied) {
        if (occupied.size() < 2) {
            return List.of();
        }
        double from = data.orbitSeconds();
        double to = from + ENCOUNTER_LOOKAHEAD;
        var system = data.system();
        var forecasts = snapshots(occupied, data).stream().map(sample -> {
            var legs = system.conics(sample.region().reference, from, to);
            var origin = sample.frame().origin();
            double reach = sample.states().values().stream().mapToDouble(state -> state.position().distanceTo(origin.position())
                    + ENCOUNTER_LOOKAHEAD * state.velocity().distanceTo(origin.velocity())).max().orElse(0);
            return new Forecast(sample.region(), system.forecast(legs), origin, system.maxAcceleration(legs), reach);
        }).toList();
        double entry = ShipServerConfig.orbitEntryDistance();
        var candidates = new ArrayList<Encounter>();
        for (int i = 0; i < forecasts.size(); i++) {
            var first = forecasts.get(i);
            for (int j = i + 1; j < forecasts.size(); j++) {
                var second = forecasts.get(j);
                double range = entry + first.reach() + second.reach();
                double drift = first.now().velocity().distanceTo(second.now().velocity()) * ENCOUNTER_LOOKAHEAD
                        + (first.acceleration() + second.acceleration()) * ENCOUNTER_LOOKAHEAD * ENCOUNTER_LOOKAHEAD / 2;
                if (first.now().position().distanceTo(second.now().position()) < range + drift
                        && OrbitalPlanner.closestApproach(first.path(), second.path(), from, to, 4).distance() < range) {
                    candidates.add(new Encounter(first.region(), second.region()));
                }
            }
        }
        return candidates;
    }

    private boolean merge(ServerLevel space, OrbitalSavedData data, Set<Region> occupied) {
        boolean changed = false;
        double entry = ShipServerConfig.orbitEntryDistance();
        double speed = ShipServerConfig.orbitEntrySpeed();
        var samples = new HashMap<Region, Snapshot>();
        for (var encounter : encounters) {
            var first = encounter.first();
            var second = encounter.second();
            if (!occupied.contains(first) || !occupied.contains(second) || first.reference == null || second.reference == null) {
                continue;
            }
            var a = samples.computeIfAbsent(first, region -> region.snapshot(data));
            var b = samples.computeIfAbsent(second, region -> region.snapshot(data));
            if (!canMerge(a.states().values(), b.states().values(), entry, speed)) {
                continue;
            }
            var source = first.members.size() < second.members.size() ? a : b;
            var target = source == a ? b : a;
            var moving = List.copyOf(source.region().members.values());
            if (shift(space, source.region(), moving, true, source.frame().shiftTo(target.frame()))) {
                moving.forEach(member -> track(target.region(), member, true));
                release(source.region());
                samples.remove(target.region());
                changed = true;
            }
        }
        return changed;
    }

    static boolean canMerge(Collection<OrbitalState> first, Collection<OrbitalState> second, double distance, double speed) {
        boolean nearby = false;
        for (var a : first) {
            for (var b : second) {
                if (a.position().distanceToSqr(b.position()) < distance * distance) {
                    if (a.velocity().distanceToSqr(b.velocity()) > speed * speed) {
                        return false;
                    }
                    nearby = true;
                }
            }
        }
        return nearby;
    }

    private static boolean shift(ServerLevel space, Region source, List<Entity> members, boolean wholeRegion, OrbitalState delta) {
        if (members.stream().anyMatch(member -> !ShipOrbitHandoff.validPosition(space,
                localState(member).position().add(delta.position())))) {
            return false;
        }
        SableShipFlight.shiftOrbitalFrame(space, members, wholeRegion ? position -> slotAt(position).equals(source.origin) : null,
                delta.position(), delta.velocity());
        return true;
    }

    private static void rebase(ServerLevel space, OrbitalSavedData data, Region region) {
        var entity = region.members.get(region.anchor);
        boolean changedAnchor = entity == null;
        if (changedAnchor) {
            entity = region.members.values().stream().findFirst().orElse(null);
        }
        if (entity == null) {
            return;
        }
        var local = localState(entity);
        Vec3 displacement = local.position().subtract(region.origin);
        if (!changedAnchor && displacement.lengthSqr() <= REBASE_DISTANCE * REBASE_DISTANCE
                && local.velocity().lengthSqr() <= REBASE_SPEED * REBASE_SPEED) {
            return;
        }
        var before = region.reference;
        var frame = region.frame(data);
        Orbit next;
        try {
            next = fit(data.system(), frame.absolute(local), frame.seconds());
        } catch (IllegalArgumentException error) {
            LOGGER.debug("Could not fit an orbital reference for {}", entity.getUUID(), error);
            return;
        }
        var nextFrame = new Frame(data.system(), next, region.origin, frame.seconds());
        if (shift(space, region, List.copyOf(region.members.values()), true, frame.shiftTo(nextFrame))) {
            region.setReference(next, entity.getUUID());
            if (!before.center().equals(next.center())) {
                postSoiChange(region, before, next);
            }
        }
    }

    private static void postSoiChange(Region region, Orbit before, Orbit after) {
        for (var member : List.copyOf(region.members.values())) {
            if (member instanceof AssembledShipEntity ship) {
                NeoForge.EVENT_BUS.post(ShipOrbitEvent.soiChanged(ship.getUUID(), ship.owner(), before, after, ship.propellant()));
            }
        }
    }

    private static boolean hasPlayer(Entity entity) {
        return entity instanceof ServerPlayer || ((AssembledShipEntity) entity).activeFlight().carriesPlayer();
    }

    private static List<Snapshot> snapshots(Collection<Region> regions, OrbitalSavedData data) {
        return regions.stream().map(region -> region.snapshot(data)).toList();
    }

    @Nullable
    private static Snapshot nearestRegion(List<Snapshot> regions, OrbitalState state) {
        double entry = ShipServerConfig.orbitEntryDistance();
        double closest = entry * entry;
        double speed = ShipServerConfig.orbitEntrySpeed();
        Snapshot result = null;
        for (var region : regions) {
            for (var peer : region.states().values()) {
                double distance = state.position().distanceToSqr(peer.position());
                if (distance < closest && state.velocity().distanceToSqr(peer.velocity()) <= speed * speed) {
                    closest = distance;
                    result = region;
                }
            }
        }
        return result;
    }

    private void exchangeShips(ServerLevel space, OrbitalSavedData data, Set<Region> occupied) {
        if (occupied.isEmpty()) {
            return;
        }
        double exit = ShipServerConfig.orbitExitDistance();
        var samples = snapshots(occupied, data);
        // Take all exit decisions from a single sample, including the original anchors.
        var justExited = new HashSet<UUID>();
        for (var sample : samples) {
            for (var entity : List.copyOf(sample.region().members.values())) {
                if (entity instanceof AssembledShipEntity ship && !pendingDescents.containsKey(ship.getUUID())
                        && !hasPlayer(ship) && sample.isolated(ship.getUUID(), exit)
                        && store(sample.region(), ship, data, sample.states().get(ship.getUUID()))) {
                    justExited.add(ship.getUUID());
                }
            }
            sample.states().keySet().removeAll(justExited);
        }
        for (var ship : List.copyOf(data.ships())) {
            if (justExited.contains(ship.id())) {
                continue;
            }
            var state = data.system().absoluteState(ship.orbit(), data.orbitSeconds());
            var sample = nearestRegion(samples, state);
            if (sample != null) {
                var restored = restoreShip(space, data, sample.frame(), ship, state);
                if (restored != null) {
                    registerEntry(sample.region(), restored, ship.orbit(), sample.frame());
                    data.remove(ship.id());
                }
            }
        }
    }

    private boolean store(Region region, AssembledShipEntity ship, OrbitalSavedData data, OrbitalState absolute) {
        try {
            var motion = ship.activeFlight().motion();
            var orbit = fit(data.system(), absolute, data.orbitSeconds());
            ShipOrbitHandoff.storeFlight(data, ship, motion, orbit, toInertialRotation());
            region.members.remove(ship.getUUID());
            assignments.remove(ship.getUUID());
            return true;
        } catch (RuntimeException error) {
            LOGGER.warn("Could not store orbital-space ship {}", ship.getUUID(), error);
            return false;
        }
    }

    @Nullable
    private static AssembledShipEntity restoreShip(ServerLevel level, OrbitalSavedData data, Frame frame, OrbitingShip ship,
            OrbitalState state) {
        var motion = frame.localMotion(state, ship.orientation(), ship.angularVelocity());
        if (!ShipOrbitHandoff.validPosition(level, motion.center())) {
            return null;
        }
        try {
            return ShipOrbitHandoff.restoreFlight(data, level, ship, motion, toInertialRotation().conjugate(), false);
        } catch (RuntimeException error) {
            LOGGER.warn("Could not restore ship {} in orbital space", ship.id(), error);
            return null;
        }
    }

    private void recycleUnoccupied(OrbitalSavedData data, Set<Region> occupied) {
        for (var region : regions.values()) {
            // A loaded reference may still be waiting for its Sable plots to attach after startup.
            if (region.reference != null && region.seen && !occupied.contains(region)) {
                recycle(region, data);
            }
        }
    }

    private void recycle(Region region, OrbitalSavedData data) {
        var frame = region.frame(data);
        boolean allStored = true;
        for (var entity : List.copyOf(region.members.values())) {
            if (entity instanceof AssembledShipEntity ship) {
                allStored &= store(region, ship, data, frame.absolute(localState(ship)));
            }
        }
        if (allStored) {
            release(region);
        }
    }

    private void release(Region region) {
        region.setReference(null, null);
        region.members.clear();
        assignments.values().removeIf(assigned -> assigned == region);
        // Slots are reusable. A forecast made for this reference must not follow its replacement.
        encounters = encounters.stream().filter(pair -> pair.first() != region && pair.second() != region).toList();
    }


    void onLogout(ServerPlayer player, OrbitalSavedData data) {
        if (!inSpace(player) || player.getPersistentData().contains(PLAYER_RETURN)) {
            return;
        }
        var space = player.serverLevel();
        timeWarp.reset(space);
        syncMembers(space);
        var region = regionFor(player);
        if (region == null) {
            configurePlayer(player, false);
            return;
        }
        savePlayer(player, data, region);
        if (!occupiedRegions(space).contains(region)) {
            recycle(region, data);
        }
    }

    void onStopping(ServerLevel space, OrbitalSavedData data) {
        timeWarp.reset(space);
        if (benchmark == null) {
            syncMembers(space);
            for (var player : List.copyOf(space.players())) {
                var region = regionFor(player);
                if (region != null && !player.getPersistentData().contains(PLAYER_RETURN)) {
                    savePlayer(player, data, region);
                }
            }
            recycleUnoccupied(data, Set.of());
        }
    }

    private static void savePlayer(ServerPlayer player, OrbitalSavedData data, Region region) {
        var tag = new CompoundTag();
        Motion motion = null;
        if (player.getRootVehicle() instanceof AssembledShipEntity ship && ship.activeFlight() != null) {
            tag.putUUID("ship", ship.getUUID());
            motion = ship.activeFlight().motion();
        }
        var local = motion == null ? localState(player) : new OrbitalState(motion.center(), motion.velocity());
        var frame = region.frame(data);
        Orbit path;
        try {
            path = fit(data.system(), frame.absolute(local), frame.seconds());
        } catch (IllegalArgumentException error) {
            path = region.reference;
        }
        tag.put("orbit", Orbit.CODEC.encodeStart(NbtOps.INSTANCE, path).getOrThrow());
        tag.putDouble("seconds", frame.seconds());
        player.getPersistentData().put(PLAYER_RETURN, tag);
        player.stopRiding();
        SableShipFlight.clearTracking(player);
        configurePlayer(player, false);
    }

    void onLogin(ServerPlayer player, OrbitalSavedData data) {
        configurePlayer(player, inSpace(player));
        var saved = player.getPersistentData().getCompound(PLAYER_RETURN);
        var server = player.serverLevel().getServer();
        var space = server.getLevel(BlockSpaceProgram.ORBITAL_SPACE);
        if (space == null || !saved.contains("orbit")) {
            return;
        }
        BenchmarkCommands.interruptOrbital(space);
        syncMembers(space);
        double now = data.orbitSeconds();
        UUID shipId = saved.hasUUID("ship") ? saved.getUUID("ship") : null;
        if (shipId != null) {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(shipId) instanceof AssembledShipEntity ship && ship.activeFlight() != null
                        && ship.activeFlight().board(player)) {
                    player.getPersistentData().remove(PLAYER_RETURN);
                    return;
                }
            }
            var ship = data.ship(shipId);
            if (ship != null) {
                var absolute = data.system().absoluteState(ship.orbit(), now);
                var region = entryRegion(space, data, absolute);
                if (region != null) {
                    var frame = region.entryFrame(data, ship.orbit());
                    var restored = restoreShip(space, data, frame, ship, absolute);
                    if (restored != null) {
                        var flight = restored.activeFlight();
                        if (flight.board(player)) {
                            data.remove(shipId);
                            registerEntry(region, restored, ship.orbit(), frame);
                            player.getPersistentData().remove(PLAYER_RETURN);
                            return;
                        }
                        flight.removeForOrbit();
                    }
                }
            }
        }
        var orbit = Orbit.CODEC.parse(NbtOps.INSTANCE, saved.get("orbit")).getOrThrow();
        double savedSeconds = saved.getDouble("seconds");
        if (savedSeconds > now) {
            // Player data can be flushed before the world's orbital clock during shutdown.
            var body = data.system().body(orbit.center());
            orbit = ShipOrbitHandoff.fit(body, data.system().relativeState(orbit, savedSeconds), now);
        } else {
            orbit = data.system().advance(orbit, savedSeconds, now);
        }
        var absolute = data.system().absoluteState(orbit, now);
        var region = entryRegion(space, data, absolute);
        if (region != null) {
            var frame = region.entryFrame(data, orbit);
            var local = frame.local(absolute);
            if (SableShipFlight.movePlayer(player, space, local.position(), local.velocity(), player.getYRot(), player.getXRot())) {
                registerEntry(region, player, orbit, frame);
                player.getPersistentData().remove(PLAYER_RETURN);
            }
        }
    }

    static void configurePlayer(ServerPlayer player, boolean inSpace) {
        var gravity = player.getAttribute(Attributes.GRAVITY);
        var abilities = player.getAbilities();
        var tag = player.getPersistentData();
        if (inSpace) {
            if (!gravity.hasModifier(ZERO_GRAVITY)) {
                gravity.addTransientModifier(new AttributeModifier(ZERO_GRAVITY, -1,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            }
            // Vanilla flight gives EVA translation and exempts zero-gravity players from the flight kick.
            if (!abilities.mayfly) {
                abilities.mayfly = true;
                tag.putBoolean(EVA_FLIGHT, true);
                player.onUpdateAbilities();
            }
        } else {
            gravity.removeModifier(ZERO_GRAVITY);
            if (tag.getBoolean(EVA_FLIGHT)) {
                tag.remove(EVA_FLIGHT);
                if (!player.isCreative() && !player.isSpectator()) {
                    abilities.mayfly = false;
                    abilities.flying = false;
                    player.onUpdateAbilities();
                }
            }
        }
    }

    List<OrbitalNetworking.Contact> contacts(OrbitalSavedData data) {
        var contacts = new ArrayList<OrbitalNetworking.Contact>();
        for (var region : regions.values()) {
            if (region.members.isEmpty()) {
                continue;
            }
            var frame = region.frame(data);
            for (var member : region.members.values()) {
                if (member instanceof AssembledShipEntity) {
                    contacts.add(new OrbitalNetworking.Contact(member.getUUID(), frame.absolute(localState(member))));
                }
            }
        }
        return contacts;
    }

    /** Authoritative inertial positions of ships currently in local orbital physics. */
    public static List<OrbitalNetworking.Contact> shipContacts(MinecraftServer server, OrbitalSavedData data) {
        return get(server).contacts(data);
    }

    @Nullable
    Vec3 observerPosition(ServerPlayer player, OrbitalSavedData data) {
        var view = viewFor(player);
        return view == null ? null : data.system().absoluteState(view.reference(), data.orbitSeconds()).position()
                .add(toInertial(SableShipFlight.worldPosition(player).subtract(view.origin())));
    }

    private static OrbitalState localState(Entity entity) {
        if (entity instanceof AssembledShipEntity ship) {
            var motion = ship.activeFlight().motion();
            return new OrbitalState(motion.center(), motion.velocity());
        }
        var player = (ServerPlayer) entity;
        return new OrbitalState(SableShipFlight.worldPosition(player),
                player.getKnownMovement().scale(OrbitalSavedData.TICKS_PER_SECOND));
    }

    private static Orbit fit(OrbitalSystem system, OrbitalState absolute, double seconds) {
        var body = system.centerAt(absolute.position(), seconds);
        return ShipOrbitHandoff.fit(body, absolute.subtract(system.bodyState(body.id(), seconds)), seconds);
    }

    public record View(Vec3 origin, Orbit reference) {}

    private record BenchmarkOrbit(Orbit reference, UUID observer) {}

    private record Forecast(Region region, DoubleFunction<OrbitalState> path, OrbitalState now, double acceleration,
                            double reach) {}

    private record Encounter(Region first, Region second) {}

    private record Snapshot(Region region, Frame frame, Map<UUID, OrbitalState> states) {
        boolean isolated(UUID id, double distance) {
            var position = states.get(id).position();
            return states.entrySet().stream().noneMatch(peer -> !peer.getKey().equals(id)
                    && position.distanceToSqr(peer.getValue().position()) <= distance * distance);
        }
    }

    private static final class Region extends SavedData {
        private final Map<UUID, Entity> members = new LinkedHashMap<>();
        private Vec3 origin = ORIGIN;
        @Nullable private Orbit reference;
        @Nullable private UUID anchor;
        private boolean seen;

        Frame frame(OrbitalSavedData data) {
            return new Frame(data.system(), reference, origin, data.orbitSeconds());
        }

        Frame entryFrame(OrbitalSavedData data, Orbit orbit) {
            return new Frame(data.system(), reference == null ? orbit : reference, origin, data.orbitSeconds());
        }

        Snapshot snapshot(OrbitalSavedData data) {
            var frame = frame(data);
            var states = new LinkedHashMap<UUID, OrbitalState>();
            members.forEach((id, entity) -> states.put(id, frame.absolute(localState(entity))));
            return new Snapshot(this, frame, states);
        }

        void setReference(@Nullable Orbit next, @Nullable UUID member) {
            reference = next;
            anchor = member;
            setDirty();
        }

        static Region load(CompoundTag tag, HolderLookup.Provider registries) {
            var region = new Region();
            if (tag.contains("reference")) {
                region.reference = Orbit.CODEC.parse(NbtOps.INSTANCE, tag.get("reference")).getOrThrow();
            }
            if (tag.hasUUID("anchor")) {
                region.anchor = tag.getUUID("anchor");
            }
            return region;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            if (reference != null) {
                tag.put("reference", Orbit.CODEC.encodeStart(NbtOps.INSTANCE, reference).getOrThrow());
            }
            if (anchor != null) {
                tag.putUUID("anchor", anchor);
            }
            return tag;
        }
    }

    record Frame(OrbitalState origin, OrbitalState relativeOrigin, Vec3 worldOrigin, double mu, double seconds) {
        Frame(OrbitalSystem system, Orbit orbit, Vec3 worldOrigin, double seconds) {
            this(system.absoluteState(orbit, seconds), system.relativeState(orbit, seconds), worldOrigin,
                    system.body(orbit.center()).gravitationalParameter(), seconds);
        }

        void configurePhysics(AssembledShipEntity ship) {
            ship.activeFlight().setOrbitalFrame(this::gravity, toLocal(relativeOrigin.velocity()));
        }

        OrbitalState absolute(OrbitalState local) {
            return origin.add(new OrbitalState(toInertial(local.position().subtract(worldOrigin)), toInertial(local.velocity())));
        }

        OrbitalState local(OrbitalState absolute) {
            var relative = absolute.subtract(origin);
            return new OrbitalState(worldOrigin.add(toLocal(relative.position())), toLocal(relative.velocity()));
        }

        OrbitalState shiftTo(Frame target) {
            var delta = origin.subtract(target.origin);
            return new OrbitalState(target.worldOrigin.subtract(worldOrigin).add(toLocal(delta.position())),
                    toLocal(delta.velocity()));
        }

        Motion localMotion(OrbitalState state, Quaterniond orientation, Vec3 angularVelocity) {
            var local = local(state);
            return new Motion(local.position(), local.velocity(),
                    toInertialRotation().conjugate().mul(orientation).normalize(), toLocal(angularVelocity));
        }

        Vec3 gravity(Vec3 position) {
            var offset = toInertial(position.subtract(worldOrigin));
            var gravityOrigin = relativeOrigin.position();
            var delta = OrbitalGravity.difference(mu, new Vector3d(gravityOrigin.x, gravityOrigin.y, gravityOrigin.z),
                    new Vector3d(offset.x, offset.y, offset.z));
            return toLocal(new Vec3(delta.x, delta.y, delta.z));
        }
    }
}

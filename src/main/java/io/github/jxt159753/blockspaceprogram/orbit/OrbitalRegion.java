package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight.Motion;
import io.github.jxt159753.blockspaceprogram.assembly.ShipServerConfig;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.slf4j.Logger;

/** A single translating, non-rotating Sable region in the orbital-space dimension. */
public final class OrbitalRegion extends SavedData {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Factory<OrbitalRegion> FACTORY = new Factory<>(OrbitalRegion::new, OrbitalRegion::load);
    private static final String STORAGE_ID = "blockspaceprogram_orbital_region";
    private static final String PLAYER_RETURN = "BlockSpaceProgramOrbitalReturn";
    private static final String EVA_FLIGHT = "BlockSpaceProgramEvaFlight";
    private static final ResourceLocation ZERO_GRAVITY =
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "orbital_gravity");
    private static final double REBASE_DISTANCE = 64;
    private static final double REBASE_SPEED = 12;
    static final int CONTACT_INTERVAL = 5;
    // Leave clearance below the region for target ships and untethered players.
    public static final Vec3 ORIGIN = new Vec3(0, 4096, 0);

    private final Map<UUID, Entity> members = new LinkedHashMap<>();
    private final OrbitalTimeWarp timeWarp = new OrbitalTimeWarp();
    @Nullable private Orbit reference;
    @Nullable private UUID anchor;
    @Nullable private BenchmarkOrbit benchmark;

    private OrbitalRegion() {}

    static OrbitalRegion get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, STORAGE_ID);
    }

    OrbitalTimeWarp timeWarp() {
        return timeWarp;
    }

    List<OrbitalState> warpMembers(ServerLevel space) {
        syncMembers(space);
        return members.values().stream().map(OrbitalRegion::localState)
                .map(state -> new OrbitalState(toInertial(state.position().subtract(ORIGIN)), toInertial(state.velocity())))
                .toList();
    }

    @Nullable
    public static Orbit referenceFor(ServerPlayer player) {
        if (!inSpace(player)) {
            return null;
        }
        var region = get(player.serverLevel().getServer());
        var benchmark = region.benchmark;
        return benchmark != null && player.getUUID().equals(benchmark.observer()) ? benchmark.reference() : region.reference;
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

    boolean availableForBenchmark(ServerLevel space) {
        return benchmark == null && reference == null && space.players().isEmpty() && SableShipFlight.activeShips(space).isEmpty();
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
        members.put(player.getUUID(), player);
        // Use the orbital dimension's base physics without tidal forces for the controlled scene.
        SableShipFlight.setOrbitalGravity(space, null);
        return true;
    }

    boolean benchmarking() {
        return benchmark != null;
    }

    void addBenchmarkShip(AssembledShipEntity ship) {
        members.put(ship.getUUID(), ship);
    }

    boolean hasBenchmarkShip(AssembledShipEntity ship) {
        return benchmark != null && members.get(ship.getUUID()) == ship;
    }

    void endBenchmark() {
        benchmark = null;
        members.clear();
    }

    void enter(OrbitalSavedData data, AssembledShipEntity ship, Orbit orbit, Motion motion, Quaterniondc rotation) {
        var space = ship.getServer().getLevel(BlockSpaceProgram.ORBITAL_SPACE);
        if (space == null) {
            return;
        }
        BenchmarkCommands.interruptOrbital(space);
        double now = data.orbitSeconds();
        Orbit path = reference == null ? orbit : reference;
        var system = data.system();
        var frame = new Frame(system, path, now);
        var transform = toInertialRotation().conjugate().mul(rotation);
        var angular = rotation.transform(new Vector3d(motion.angularVelocity().x, motion.angularVelocity().y,
                motion.angularVelocity().z));
        var local = frame.localMotion(system.absoluteState(orbit, now), new Quaterniond(rotation).mul(motion.orientation()),
                new Vec3(angular.x, angular.y, angular.z));
        var restored = ship.activeFlight().transfer(space, local, transform, false);
        if (restored != null) {
            members.put(restored.getUUID(), restored);
            if (reference == null) {
                setReference(orbit, restored.getUUID());
            }
        }
    }

    void tick(ServerLevel space, OrbitalSavedData data, double previousSeconds, boolean warped) {
        if (benchmark != null) {
            syncMembers(space);
            return;
        }
        if (reference == null) {
            return;
        }
        boolean wasActive = !members.isEmpty();
        syncMembers(space);
        double now = data.orbitSeconds();
        if (space.players().stream().noneMatch(ServerPlayer::isAlive)) {
            if (wasActive || !members.isEmpty()) {
                recycle(space, data);
            }
            return;
        }

        var system = data.system();
        var advanced = system.advance(reference, previousSeconds, now);
        if (advanced != reference) {
            setReference(advanced, anchor);
        }
        var frame = new Frame(system, reference, now);
        if (warped) {
            SableShipFlight.setOrbitalGravity(space, frame::gravity);
            return;
        }
        for (Entity member : List.copyOf(members.values())) {
            if (member instanceof AssembledShipEntity ship) {
                try {
                    if (ShipOrbitHandoff.returnFromRegion(space.getServer(), system, ship,
                            frame.absolute(localState(ship)), now)) {
                        members.remove(ship.getUUID());
                    }
                } catch (RuntimeException error) {
                    LOGGER.warn("Could not return ship {} from orbital space", ship.getUUID(), error);
                }
            }
        }
        syncMembers(space);
        if (space.players().stream().noneMatch(ServerPlayer::isAlive)) {
            recycle(space, data);
            return;
        }
        if (data.orbitTicks() % CONTACT_INTERVAL == 0) {
            exchangeShips(space, data, frame);
        }
        rebase(space, system, frame, now);
        var gravityFrame = new Frame(system, reference, now);
        SableShipFlight.setOrbitalGravity(space, gravityFrame::gravity);
    }

    private void syncMembers(ServerLevel space) {
        var active = new HashSet<UUID>();
        for (var ship : SableShipFlight.activeShips(space)) {
            active.add(ship.getUUID());
            members.put(ship.getUUID(), ship);
        }
        for (ServerPlayer player : space.players()) {
            if (player.isAlive() && !(player.getRootVehicle() instanceof AssembledShipEntity)) {
                active.add(player.getUUID());
                members.put(player.getUUID(), player);
            }
        }
        members.keySet().retainAll(active);
    }

    private void rebase(ServerLevel space, OrbitalSystem system, Frame frame, double now) {
        var entity = members.get(anchor);
        boolean changedAnchor = entity == null;
        if (changedAnchor) {
            entity = members.values().stream().findFirst().orElse(null);
        }
        if (entity == null) {
            return;
        }
        var local = localState(entity);
        Vec3 displacement = local.position().subtract(ORIGIN);
        if (changedAnchor || displacement.lengthSqr() > REBASE_DISTANCE * REBASE_DISTANCE
                || local.velocity().lengthSqr() > REBASE_SPEED * REBASE_SPEED) {
            try {
                Orbit next = fit(system, frame.absolute(local), now);
                SableShipFlight.rebase(space, displacement.scale(-1), local.velocity().scale(-1));
                setReference(next, entity.getUUID());
            } catch (IllegalArgumentException error) {
                LOGGER.debug("Could not fit an orbital reference for {}", entity.getUUID(), error);
            }
        }
    }

    private void exchangeShips(ServerLevel space, OrbitalSavedData data, Frame frame) {
        double entry = ShipServerConfig.orbitEntryDistance();
        double exit = ShipServerConfig.orbitExitDistance();
        double speed = ShipServerConfig.orbitEntrySpeed();
        var states = new LinkedHashMap<UUID, OrbitalState>();
        members.forEach((id, entity) -> states.put(id, frame.absolute(localState(entity))));

        // Take the exit decisions from one sample, including the original anchor.
        var justExited = new HashSet<UUID>();
        for (var entity : List.copyOf(members.values())) {
            if (entity instanceof AssembledShipEntity ship && !ship.activeFlight().carriesPlayer()) {
                var state = states.get(ship.getUUID());
                boolean isolated = states.entrySet().stream().noneMatch(peer -> !peer.getKey().equals(ship.getUUID())
                        && state.position().distanceToSqr(peer.getValue().position()) <= exit * exit);
                if (isolated && store(ship, data, state)) {
                    justExited.add(ship.getUUID());
                }
            }
        }
        justExited.forEach(states::remove);

        for (var ship : List.copyOf(data.ships())) {
            if (justExited.contains(ship.id())) {
                continue;
            }
            var state = data.system().absoluteState(ship.orbit(), frame.seconds());
            if (states.values().stream().anyMatch(peer -> state.position().distanceToSqr(peer.position()) < entry * entry
                    && state.velocity().distanceToSqr(peer.velocity()) <= speed * speed)) {
                var restored = restoreShip(space, frame, ship, state);
                if (restored != null) {
                    members.put(restored.getUUID(), restored);
                    data.remove(ship.id());
                }
            }
        }
    }

    private boolean store(AssembledShipEntity ship, OrbitalSavedData data, OrbitalState absolute) {
        try {
            var flight = ship.activeFlight();
            var motion = flight.motion();
            var orbit = fit(data.system(), absolute, data.orbitSeconds());
            data.put(new OrbitingShip(ship.getUUID(), orbit, toInertialRotation().mul(motion.orientation()),
                    toInertial(motion.angularVelocity()), ship.orbitSnapshot()));
            flight.removeForOrbit();
            members.remove(ship.getUUID());
            return true;
        } catch (RuntimeException error) {
            LOGGER.warn("Could not store orbital-space ship {}", ship.getUUID(), error);
            return false;
        }
    }

    @Nullable
    private static AssembledShipEntity restoreShip(ServerLevel level, Frame frame, OrbitingShip ship, OrbitalState state) {
        var motion = frame.localMotion(state, ship.orientation(), ship.angularVelocity());
        if (!ShipOrbitHandoff.validPosition(level, motion.center())) {
            return null;
        }
        try {
            return AssembledShipEntity.restoreFromOrbit(level, ship.id(), ship.shipData(), motion, false);
        } catch (RuntimeException error) {
            LOGGER.warn("Could not restore ship {} in orbital space", ship.id(), error);
            return null;
        }
    }

    private void recycle(ServerLevel space, OrbitalSavedData data) {
        var frame = new Frame(data.system(), reference, data.orbitSeconds());
        boolean allStored = true;
        for (var ship : SableShipFlight.activeShips(space)) {
            allStored &= store(ship, data, frame.absolute(localState(ship)));
        }
        if (allStored) {
            setReference(null, null);
            members.clear();
            SableShipFlight.setOrbitalGravity(space, null);
        }
    }

    void onLogout(ServerPlayer player, OrbitalSavedData data) {
        if (!inSpace(player)) {
            return;
        }
        timeWarp.reset(player.serverLevel());
        if (reference == null) {
            configurePlayer(player, false);
            return;
        }
        savePlayer(player, data);
        if (player.serverLevel().players().stream().noneMatch(other -> other != player && other.isAlive())) {
            recycle(player.serverLevel(), data);
        }
    }

    void onStopping(ServerLevel space, OrbitalSavedData data) {
        timeWarp.reset(space);
        if (reference != null) {
            List.copyOf(space.players()).forEach(player -> savePlayer(player, data));
            recycle(space, data);
        }
    }

    private void savePlayer(ServerPlayer player, OrbitalSavedData data) {
        var system = data.system();
        double now = data.orbitSeconds();
        var tag = new CompoundTag();
        Motion motion = null;
        if (player.getRootVehicle() instanceof AssembledShipEntity ship && ship.activeFlight() != null) {
            tag.putUUID("ship", ship.getUUID());
            motion = ship.activeFlight().motion();
        }
        var local = motion == null ? localState(player) : new OrbitalState(motion.center(), motion.velocity());
        Orbit path;
        try {
            path = fit(system, new Frame(system, reference, now).absolute(local), now);
        } catch (IllegalArgumentException error) {
            path = reference;
        }
        tag.put("orbit", Orbit.CODEC.encodeStart(NbtOps.INSTANCE, path).getOrThrow());
        tag.putDouble("seconds", now);
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
                Orbit path = reference == null ? ship.orbit() : reference;
                var frame = new Frame(data.system(), path, now);
                var restored = restoreShip(space, frame, ship, data.system().absoluteState(ship.orbit(), now));
                if (restored != null) {
                    var flight = restored.activeFlight();
                    if (flight.board(player)) {
                        data.remove(shipId);
                        members.put(shipId, restored);
                        if (reference == null) {
                            setReference(path, shipId);
                        }
                        player.getPersistentData().remove(PLAYER_RETURN);
                        return;
                    }
                    flight.removeForOrbit();
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
        Orbit path = reference == null ? orbit : reference;
        var frame = new Frame(data.system(), path, now);
        var local = frame.local(data.system().absoluteState(orbit, now));
        if (SableShipFlight.movePlayer(player, space, local.position(), local.velocity(), player.getYRot(), player.getXRot())) {
            if (reference == null) {
                setReference(path, player.getUUID());
            }
            members.put(player.getUUID(), player);
            player.getPersistentData().remove(PLAYER_RETURN);
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
        if (reference == null) {
            return List.of();
        }
        var frame = new Frame(data.system(), reference, data.orbitSeconds());
        return members.values().stream().filter(AssembledShipEntity.class::isInstance)
                .map(ship -> new OrbitalNetworking.Contact(ship.getUUID(), frame.absolute(localState(ship))))
                .toList();
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

    private void setReference(@Nullable Orbit next, @Nullable UUID member) {
        reference = next;
        anchor = member;
        setDirty();
    }

    private static OrbitalRegion load(CompoundTag tag, HolderLookup.Provider registries) {
        var region = new OrbitalRegion();
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

    private record BenchmarkOrbit(Orbit reference, UUID observer) {}

    private record Frame(OrbitalState origin, Vec3 gravityOrigin, double mu, double seconds) {
        Frame(OrbitalSystem system, Orbit orbit, double seconds) {
            this(system.absoluteState(orbit, seconds), system.relativeState(orbit, seconds).position(),
                    system.body(orbit.center()).gravitationalParameter(), seconds);
        }

        OrbitalState absolute(OrbitalState local) {
            return origin.add(new OrbitalState(toInertial(local.position().subtract(ORIGIN)), toInertial(local.velocity())));
        }

        OrbitalState local(OrbitalState absolute) {
            var relative = absolute.subtract(origin);
            return new OrbitalState(ORIGIN.add(toLocal(relative.position())), toLocal(relative.velocity()));
        }

        Motion localMotion(OrbitalState state, Quaterniond orientation, Vec3 angularVelocity) {
            var local = local(state);
            return new Motion(local.position(), local.velocity(),
                    toInertialRotation().conjugate().mul(orientation).normalize(), toLocal(angularVelocity));
        }

        Vec3 gravity(Vec3 position) {
            var offset = toInertial(position.subtract(ORIGIN));
            var delta = OrbitalGravity.difference(mu, new Vector3d(gravityOrigin.x, gravityOrigin.y, gravityOrigin.z),
                    new Vector3d(offset.x, offset.y, offset.z));
            return toLocal(new Vec3(delta.x, delta.y, delta.z));
        }
    }
}

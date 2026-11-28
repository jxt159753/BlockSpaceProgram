package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSableSubLevelContainerReadyEvent;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDocking.CaptureLimits;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDocking.Port;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDocking.PortKey;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionBody;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.slf4j.Logger;

import static io.github.jxt159753.blockspaceprogram.assembly.ShipFlightInput.*;

/** Binds a ship and its saved flight state to a single Sable plot. Loaded only with Sable present. */
public final class SableShipFlight implements ShipFlightControl {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String OWNER = "BlockSpaceProgramShip";
    private static final int INPUT_TIMEOUT = 10;
    private static final double STANDARD_AIR_DENSITY = 1.225;
    private static final double SEPARATION_SPEED = 2;
    private static final double LANDING_SPEED_SQUARED = 0.25;
    private static final int LANDING_CONTACT_TICKS = 5;
    // Keep these bounded bodies loaded until the cleanup rules have removed them.
    private static final SubLevelLoadingTicketType<Unit> DEBRIS_TICKET = SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "debris"), Unit.CODEC);
    private static final SubLevelLoadingTicketType<Unit> HANDOFF_TICKET = SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "orbit_handoff"), Unit.CODEC);
    private static final SubLevelLoadingTicketType<Unit> REMOTE_TICKET = SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "remote_flight"), Unit.CODEC);
    private static final Map<ServerLevel, Flights> LEVELS = new IdentityHashMap<>();

    private final AssembledShipEntity ship;
    private final ShipCollisionBody body;
    private ShipFlightState state;
    @Nullable private ShipDesign overBudgetClosure;
    private final ShipFlightControls controls;
    private final ShipLandingGear landingGear;
    private final Flights flights;
    @Nullable private UnaryOperator<Vec3> orbitalGravity;
    private Vec3 orbitalVelocity = Vec3.ZERO;
    private double previousHandoffY;
    private int keys;
    private int pressedKeys;
    private boolean stageRequested;
    private long inputExpiresAt = Long.MIN_VALUE;
    private boolean remoteTicket;
    private boolean rcsEnabled;
    private double thrust;
    private boolean fuelDepleted;
    private boolean electricDepleted;
    private long supportAt = Long.MIN_VALUE;
    private boolean airborne;
    private boolean landed;
    private final Vector3f reentryWindward = new Vector3f();

    private SableShipFlight(AssembledShipEntity ship, ShipCollisionBody body, ShipFlightState state, ShipDesign design) {
        this.ship = ship;
        this.body = body;
        this.state = state;
        this.controls = ship.flightControls();
        this.landingGear = new ShipLandingGear(design);
        this.flights = LEVELS.get(body.subLevel().getLevel());
        this.previousHandoffY = body.subLevel().logicalPose().position().y();
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener((ForgeSableSubLevelContainerReadyEvent event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                // The running Sable pipeline keeps the base gravity it received at initialization.
                LEVELS.put(level, new Flights(DimensionPhysicsData.getGravity(level)));
            }
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> LEVELS.remove(event.getLevel()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) ->
                LEVELS.values().forEach(flights -> flights.active.values().forEach(flight -> flight.setRemoteTicket(false))));
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                checkDocking(level);
                if (level.getGameTime() % 20 == 0) {
                    cleanupDebris(level);
                }
            }
        });
        NeoForge.EVENT_BUS.addListener(ShipDockingCommands::register);
        SableHullInteractions.register();
        NeoForge.EVENT_BUS.addListener((ForgeSablePrePhysicsTickEvent event) -> {
            var flights = LEVELS.get(event.getPhysicsSystem().getLevel());
            if (flights != null) {
                for (SableShipFlight flight : flights.active.values()) {
                    if (!flight.body.subLevel().isRemoved()) {
                        flight.step(event.getTimeStep());
                    }
                }
            }
        });
        NeoForge.EVENT_BUS.addListener((ForgeSablePostPhysicsTickEvent event) -> {
            var flights = LEVELS.get(event.getPhysicsSystem().getLevel());
            if (flights != null) {
                for (SableShipFlight flight : flights.active.values()) {
                    if (!flight.body.subLevel().isRemoved() && flight.state.applyDamage(flight.body)) {
                        flight.ship.setDamagedParts(flight.state.damagedParts());
                    }
                }
            }
        });
    }

    // The contact callback runs inside the solver. Apply changes only after the substep returns.
    static void recordImpact(ServerLevel level, BlockPos cell, Vector3dc point, double speed) {
        var flights = LEVELS.get(level);
        if (flights != null) {
            var flight = flights.active.get(Sable.HELPER.getContaining(level, cell));
            if (flight != null) {
                flight.state.recordImpact(flight.body.plotOrigin(), cell, point, speed);
                var pose = flight.body.subLevel().logicalPose();
                if (pose.transformPosition(new Vector3d(point)).y < pose.position().y()) {
                    flight.supportAt = level.getGameTime();
                }
            }
        }
    }

    static SableShipFlight launch(AssembledShipEntity ship, ServerLevel level, ShipDesign design) {
        if (design.nodes().stream().noneMatch(node -> node.dryMass() > 0)) {
            throw new IllegalArgumentException("A flying ship needs positive dry mass");
        }
        var state = new ShipFlightState(design, ShipPropellantData.EMPTY, new BitSet(), ShipFlightState.initialStage(design));
        var origin = new Vector3d(ship.getX(), ship.getY(), ship.getZ());
        var rotation = new Quaterniond(PartPose.orientation(ship.launchRotation())).normalize();
        var flight = spawn(ship, level, design, state, origin, rotation);
        ship.setPos(flight.plotPosition());
        return flight;
    }

    private static SableShipFlight spawn(AssembledShipEntity ship, ServerLevel level, ShipDesign design,
            ShipFlightState state, Vector3dc origin, Quaterniondc orientation) {
        var body = ShipCollisionBody.spawn(level, ship.getName().getString(), state.cells(), state.hull(), origin, orientation);
        var tag = new CompoundTag();
        tag.putUUID(OWNER, ship.getUUID());
        body.subLevel().setUserDataTag(tag);
        return new SableShipFlight(ship, body, state, design);
    }

    static boolean obstructs(Level level, AABB bounds) {
        for (var subLevel : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(bounds))) {
            if (!subLevel.isRemoved() && subLevel.boundingBox().toMojang().intersects(bounds)) {
                return true;
            }
        }
        return false;
    }

    static SableShipFlight restore(AssembledShipEntity ship, ServerLevel level, ShipDesign design, UUID bodyId,
            ShipPropellantData savedPropellant, int savedStage) {
        var container = SubLevelContainer.getContainer(level);
        if (!(container.getSubLevel(bodyId) instanceof ServerSubLevel subLevel) || subLevel.isRemoved()) {
            throw new IllegalStateException("Saved Sable sub-level is missing");
        }
        var origin = ship.blockPosition();
        var tag = subLevel.getUserDataTag();
        if (tag == null || !tag.hasUUID(OWNER) || !ship.getUUID().equals(tag.getUUID(OWNER))
                || !subLevel.getPlot().contains(ship.chunkPosition())) {
            throw new IllegalStateException("Saved Sable sub-level belongs to a different ship");
        }
        var state = savedState(ship, design, savedPropellant, savedStage);
        var body = new ShipCollisionBody(container, subLevel, origin);
        body.restoreBlocks(state.cells(), state.hull());
        return new SableShipFlight(ship, body, state, design);
    }

    static boolean hasHullAt(ServerLevel level, BlockPos pos) {
        var flights = LEVELS.get(level);
        var flight = flights == null ? null : flights.active.get(Sable.HELPER.getContaining(level, pos));
        return flight != null && flight.isActive() && !flight.state.hull().isEmpty();
    }

    static SableShipFlight rebuild(AssembledShipEntity ship, ServerLevel level, ShipDesign design, ShipPropellantData savedPropellant,
            Motion motion) {
        var state = savedState(ship, design, savedPropellant, ship.activeStage());
        return spawn(ship, level, design, state, vector(motion.center()), motion.orientation());
    }

    private static ShipFlightState savedState(AssembledShipEntity ship, ShipDesign design, ShipPropellantData savedPropellant,
            int savedStage) {
        int stage = savedStage < -1 ? ShipFlightState.initialStage(design) : Math.min(savedStage, design.stageCount() - 1);
        return new ShipFlightState(design, savedPropellant, ship.damagedParts(), stage);
    }

    void attach() {
        flights.active.put(body.subLevel(), this);
        if (isInSpace() || ship.isBenchmark()) {
            body.container().addForceLoadTicket(body.subLevel(), HANDOFF_TICKET, Unit.INSTANCE);
        }
        if (isDebris()) {
            body.container().addForceLoadTicket(body.subLevel(), DEBRIS_TICKET, Unit.INSTANCE);
        }
        if (controls.remote() != null) {
            setRemoteTicket(true);
        } else {
            // A persisted ticket from an unclean shutdown cannot still have a connected computer.
            body.container().removeForceLoadTicket(body.subLevel(), REMOTE_TICKET, Unit.INSTANCE);
        }
    }

    void detach() {
        setRemoteTicket(false);
        flights.active.remove(body.subLevel(), this);
    }

    void discard() {
        detach();
        if (isDebris()) {
            body.container().removeForceLoadTicket(body.subLevel(), DEBRIS_TICKET, Unit.INSTANCE);
        }
        body.container().removeForceLoadTicket(body.subLevel(), HANDOFF_TICKET, Unit.INSTANCE);
        body.subLevel().markRemoved();
    }

    static List<AssembledShipEntity> ownedShips(ServerLevel level, UUID owner) {
        var flights = LEVELS.get(level);
        return flights == null ? List.of() : flights.active.values().stream()
                .filter(flight -> flight.isActive() && !flight.isDebris() && owner.equals(flight.ship.owner()))
                .map(flight -> flight.ship).toList();
    }

    public void removeBodyNow() {
        // Handoffs can be followed by a save before Sable processes its removal queue.
        body.remove();
    }

    UUID bodyId() {
        return body.subLevel().getUniqueId();
    }

    Vec3 plotPosition() {
        return Vec3.atLowerCornerOf(body.plotOrigin());
    }

    public static List<AssembledShipEntity> takeAscents(ServerLevel level, double ascentY) {
        var flights = LEVELS.get(level);
        if (flights == null) {
            return List.of();
        }
        List<AssembledShipEntity> ascending = new ArrayList<>();
        for (var flight : flights.active.values()) {
            var subLevel = flight.body.subLevel();
            if (subLevel.isRemoved() || flight.ship.isBenchmark()) {
                continue;
            }
            double y = subLevel.logicalPose().position().y();
            if (y < level.getMaxBuildHeight() && flight.previousHandoffY >= level.getMaxBuildHeight()) {
                flight.body.container().removeForceLoadTicket(subLevel, HANDOFF_TICKET, Unit.INSTANCE);
            }
            if (flight.previousHandoffY <= ascentY && y > ascentY) {
                ascending.add(flight.ship);
            }
            flight.previousHandoffY = y;
        }
        return ascending;
    }

    public boolean carriesPlayer() {
        return ship.getSelfAndPassengers().anyMatch(Player.class::isInstance)
                || body.subLevel().getLevel().players().stream().anyMatch(this::carries);
    }

    public boolean carries(ServerPlayer player) {
        var subLevel = body.subLevel();
        return player.getRootVehicle() == ship || Sable.HELPER.getTrackingOrVehicleSubLevel(player) == subLevel
                || Sable.HELPER.getContaining(player) == subLevel;
    }

    @Nullable
    public static AssembledShipEntity carriedShip(ServerPlayer player) {
        if (player.getRootVehicle() instanceof AssembledShipEntity ship && ship.activeFlight() != null) {
            return ship;
        }
        var flights = LEVELS.get(player.serverLevel());
        if (flights == null) {
            return null;
        }
        var flight = flights.active.get(Sable.HELPER.getTrackingOrVehicleSubLevel(player));
        if (flight == null) {
            flight = flights.active.get(Sable.HELPER.getContaining(player));
        }
        return flight != null && flight.isActive() ? flight.ship : null;
    }

    private boolean isInSpace() {
        return ship.level().dimension().equals(BlockSpaceProgram.ORBITAL_SPACE);
    }

    public static List<AssembledShipEntity> activeShips(ServerLevel level) {
        var flights = LEVELS.get(level);
        return flights == null ? List.of() : flights.active.values().stream().filter(SableShipFlight::isActive)
                .map(flight -> flight.ship).toList();
    }

    public void setOrbitalFrame(UnaryOperator<Vec3> gravity, Vec3 referenceVelocity) {
        orbitalGravity = gravity;
        orbitalVelocity = referenceVelocity;
    }

    public static boolean isCoasting(ServerLevel level) {
        var flights = LEVELS.get(level);
        return flights == null || flights.active.values().stream().filter(SableShipFlight::isActive)
                .noneMatch(flight -> flight.stageRequested || flight.controls.requestsThrust(flight.keys));
    }

    public static void freezeOrbitalPhysics(ServerLevel level, boolean freeze) {
        var flights = LEVELS.get(level);
        if (flights == null || flights.frozenForWarp == freeze) {
            return;
        }
        // Pausing this Sable solver retains the body poses and velocities and keeps its plots loaded.
        var physics = SubLevelContainer.getContainer(level).physicsSystem();
        if (freeze) {
            flights.pausedBeforeWarp = physics.getPaused();
        }
        physics.setPaused(freeze || flights.pausedBeforeWarp);
        flights.frozenForWarp = freeze;
    }

    /** Change the local frame while keeping the existing Sable bodies, plots and mounts. */
    public static void shiftOrbitalFrame(ServerLevel level, Collection<Entity> members, @Nullable Predicate<Vec3> loose,
            Vec3 displacement, Vec3 velocityChange) {
        var ships = members.stream().filter(AssembledShipEntity.class::isInstance)
                .map(AssembledShipEntity.class::cast).toList();
        var plots = new HashSet<ServerSubLevel>();
        ships.forEach(ship -> plots.add(ship.activeFlight().body.subLevel()));
        var moving = new IdentityHashMap<Entity, Vec3>();
        Vec3 perTick = velocityChange.scale(1.0 / 20);
        for (Entity entity : level.getAllEntities()) {
            // Passengers and entities in plot coordinates follow the body itself.
            if (entity.isPassenger() || Sable.HELPER.getContaining(entity) != null) {
                continue;
            }
            var tracking = Sable.HELPER.getTrackingSubLevel(entity);
            if (plots.contains(tracking)) {
                moving.put(entity, Vec3.ZERO);
            } else if (entity instanceof ServerPlayer ? members.contains(entity)
                    : loose != null && tracking == null && loose.test(entity.position())) {
                moving.put(entity, perTick);
            }
        }
        for (var ship : ships) {
            var flight = ship.activeFlight();
            var before = flight.motion();
            flight.setMotion(new Motion(before.center().add(displacement), before.velocity().add(velocityChange),
                    before.orientation(), before.angularVelocity()));
            ship.getPassengers().forEach(ship::positionRider);
        }
        moving.forEach((entity, boost) -> {
            if (entity instanceof ServerPlayer player) {
                var velocity = player.getKnownMovement().add(boost);
                player.teleportRelative(displacement.x, displacement.y, displacement.z);
                // A teleport confirmed before the next connection tick must use the new movement baseline.
                player.connection.resetPosition();
                setPlayerVelocity(player, velocity);
            } else {
                entity.setPos(entity.position().add(displacement));
                entity.setDeltaMovement(entity.getDeltaMovement().add(boost));
                entity.hurtMarked = true;
            }
        });
    }

    public static Vec3 worldPosition(Entity entity) {
        return vec(Sable.HELPER.projectOutOfSubLevel(entity.level(), vector(entity.position())));
    }

    public static void clearTracking(ServerPlayer player) {
        ((EntityMovementExtension) player).sable$setTrackingSubLevel(null);
    }

    private Vec3 seatPosition(ServerPlayer player, int pod) {
        Vec3 local = ShipFlightState.seat(ship.design(), pod);
        var attachment = player.getVehicleAttachmentPoint(ship);
        return worldPoint(local.subtract(attachment));
    }

    public Vec3 velocityAt(Vec3 position) {
        var motion = motion();
        return motion.velocity().add(motion.angularVelocity().cross(position.subtract(motion.center())));
    }

    public void inheritDismountVelocity(ServerPlayer player) {
        if (isInSpace()) {
            setPlayerVelocity(player, velocityAt(worldPosition(player)).scale(1.0 / 20));
        }
    }

    public static void setPlayerVelocity(ServerPlayer player, Vec3 perTick) {
        player.setDeltaMovement(perTick);
        player.setKnownMovement(perTick);
        if (player.connection != null) {
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
        }
    }

    public static boolean movePlayer(ServerPlayer player, ServerLevel level, Vec3 position, Vec3 velocity,
            float yaw, float pitch) {
        if (player.serverLevel() == level) {
            player.connection.teleport(position.x, position.y, position.z, yaw, pitch);
            player.connection.resetPosition();
        } else if (player.changeDimension(new DimensionTransition(level, position, velocity.scale(1.0 / 20),
                yaw, pitch, DimensionTransition.PLACE_PORTAL_TICKET)) == null) {
            return false;
        }
        clearTracking(player);
        setPlayerVelocity(player, velocity.scale(1.0 / 20));
        return true;
    }

    public boolean board(ServerPlayer player) {
        if (player.getVehicle() == ship) {
            return true;
        }
        int pod = ship.availableSeat(player);
        return pod >= 0 && movePlayer(player, body.subLevel().getLevel(), seatPosition(player, pod),
                Vec3.ZERO, player.getYRot(), player.getXRot()) && mount(player, pod);
    }

    private boolean mount(ServerPlayer player, int pod) {
        ship.assignSeat(player, pod);
        if (player.getVehicle() != ship && !player.startRiding(ship, true)) {
            ship.clearSeat(player);
            return false;
        }
        clearTracking(player);
        ship.positionRider(player);
        return true;
    }

    /** Rebuild the body before moving its occupants, and leave the source intact if travel is rejected. */
    @Nullable
    public AssembledShipEntity transfer(ServerLevel target, Motion after, Quaterniondc rotation, boolean reentry) {
        var before = motion();
        var source = body.subLevel().getLevel();
        boolean hasHull = ship.design().hasHull();
        List<Crew> crew = source.players().stream().filter(this::carries).map(player -> {
            Vec3 position = worldPosition(player);
            int pod = player.getVehicle() == ship ? ship.assignedSeat(player) : -1;
            return new Crew(player, pod, position, velocityAt(position),
                    player.getYRot(), player.getXRot(), hasHull && !player.isPassenger());
        }).toList();
        var replacement = AssembledShipEntity.restoreFromOrbit(target, ship.getUUID(), ship.orbitSnapshot(), after, reentry);
        if (replacement == null) {
            return null;
        }
        var next = replacement.activeFlight();
        next.keys = keys;
        next.pressedKeys = pressedKeys;
        next.stageRequested = stageRequested;
        try {
            next.inheritHandoffControls(controls, rotation);
        } catch (RuntimeException error) {
            next.removeForOrbit();
            throw error;
        }
        if (inputExpiresAt > source.getGameTime()) {
            next.inputExpiresAt = target.getGameTime() + inputExpiresAt - source.getGameTime();
        }
        var moved = new ArrayList<Crew>();
        for (var member : crew) {
            var player = member.player();
            Vec3 position = member.pod() >= 0 ? next.seatPosition(player, member.pod())
                    : after.center().add(vec(rotation.transform(vector(member.position().subtract(before.center())))));
            Vec3 velocity = member.standing() ? Vec3.ZERO : next.velocityAt(position);
            if (movePlayer(player, target, position, velocity, member.yaw(), member.pitch())) {
                moved.add(member);
                if (member.standing()) {
                    SableHullInteractions.awaitPlot(player, next.body.subLevel(), position);
                }
                if (member.pod() < 0 || next.mount(player, member.pod())) {
                    continue;
                }
            }
            for (var previous : moved.reversed()) {
                Vec3 savedVelocity = previous.standing() ? Vec3.ZERO : previous.velocity();
                if (movePlayer(previous.player(), source, previous.position(), savedVelocity, previous.yaw(), previous.pitch())) {
                    if (previous.standing()) {
                        SableHullInteractions.awaitPlot(previous.player(), body.subLevel(), previous.position());
                    } else if (previous.pod() >= 0) {
                        mount(previous.player(), previous.pod());
                    }
                }
            }
            next.removeForOrbit();
            return null;
        }
        // A client that changed levels can reuse the source plot's network address in its new level.
        moved.forEach(member -> body.subLevel().getTrackingPlayers().remove(member.player().getUUID()));
        removeForOrbit();
        return replacement;
    }

    public ShipFlightControls handoffControls(Quaterniondc rotation, Quaterniondc orientation) {
        var snapshot = new ShipFlightControls();
        snapshot.inheritForHandoff(controls, rotation, orientation);
        return snapshot;
    }

    public void inheritHandoffControls(ShipFlightControls source, Quaterniondc rotation) {
        var orientation = controlOrientation();
        controls.inheritForHandoff(source, rotation, orientation);
        if (controls.remote() != null) {
            if (hasRemoteTicketCapacity()) {
                setRemoteTicket(true);
            } else {
                controls.disconnect(orientation);
            }
        }
        syncStatus();
    }

    public void removeForOrbit() {
        ship.getSelfAndPassengers().filter(entity -> !(entity instanceof Player)).toList().reversed().forEach(Entity::discard);
        removeBodyNow();
    }

    boolean isActive() {
        return !body.subLevel().isRemoved() && flights.active.get(body.subLevel()) == this;
    }

    AABB worldBounds() {
        return body.subLevel().boundingBox().toMojang();
    }

    public Motion motion() {
        var subLevel = body.subLevel();
        var pose = subLevel.logicalPose();
        var pipeline = body.pipeline();
        return new Motion(vec(pose.position()), vec(pipeline.getLinearVelocity(subLevel, new Vector3d())),
                new Quaterniond(pose.orientation()), vec(pipeline.getAngularVelocity(subLevel, new Vector3d())));
    }

    private Vec3 worldPoint(Vec3 local) {
        return vec(body.subLevel().logicalPose().transformPosition(vector(local.add(plotPosition()))));
    }

    private double mass() {
        return body.subLevel().getMassTracker().getMass();
    }

    private ShipMomentum momentum(Motion motion, Vector3dc reference) {
        var mass = body.subLevel().getMassTracker();
        return ShipMomentum.of(mass.getMass(), mass.getInertiaTensor(), motion.orientation(), vector(motion.center()),
                vector(motion.velocity()), vector(motion.angularVelocity()), reference);
    }

    private List<Rider> riders() {
        return ship.getPassengers().stream().filter(ServerPlayer.class::isInstance).map(ServerPlayer.class::cast)
                .map(player -> new Rider(player, ship.assignedSeat(player))).toList();
    }

    private List<HullRider> hullRiders(ShipDesign design) {
        if (!design.hasHull()) {
            return List.of();
        }
        var result = new ArrayList<HullRider>();
        for (ServerPlayer player : body.subLevel().getLevel().players()) {
            if (player.isPassenger() || !carries(player)) {
                continue;
            }
            var position = body.subLevel().logicalPose().transformPositionInverse(vector(worldPosition(player)));
            Vec3 local = vec(position).subtract(plotPosition());
            BlockPos floor = BlockPos.containing(local.add(0, -0.05, 0));
            int hull = -1;
            for (int index = 0; index < design.nodes().size(); index++) {
                var node = design.nodes().get(index);
                if (node.hull() != null) {
                    if (node.hull().contains(floor.subtract(node.hullOrigin()))) {
                        hull = index;
                        break;
                    }
                    if (node.bounds().inflate(0.05).contains(local)) {
                        hull = index;
                    }
                }
            }
            if (hull >= 0) {
                result.add(new HullRider(player, hull, local));
            }
        }
        return result;
    }

    private void moveRider(ServerPlayer player, int pod) {
        if (!mount(player, pod)) {
            player.stopRiding();
            var position = seatPosition(player, pod);
            movePlayer(player, body.subLevel().getLevel(), position, velocityAt(position), player.getYRot(), player.getXRot());
        }
    }

    private void moveHullRider(ServerPlayer player, Vec3 local) {
        Vec3 position = worldPoint(local);
        if (movePlayer(player, body.subLevel().getLevel(), position, Vec3.ZERO, player.getYRot(), player.getXRot())) {
            SableHullInteractions.awaitPlot(player, body.subLevel(), position);
        }
    }

    private boolean hasPilot() {
        return ship.getFirstPassenger() instanceof ServerPlayer player && player.isAlive() && !player.isSpectator();
    }

    private Quaterniond pilotRotation() {
        if (!hasPilot()) {
            return new Quaterniond();
        }
        var pod = ship.design().nodes().get(ship.assignedSeat(ship.getFirstPassenger()));
        return new Quaterniond(PartPose.orientation(pod.pose().rotation())).normalize();
    }

    private Quaterniond controlOrientation() {
        return new Quaterniond(body.subLevel().logicalPose().orientation()).mul(pilotRotation());
    }

    public void setMotion(Motion motion) {
        var subLevel = body.subLevel();
        var pipeline = body.pipeline();
        pipeline.teleport(subLevel, vector(motion.center()), motion.orientation());
        pipeline.resetVelocity(subLevel);
        pipeline.addLinearAndAngularVelocity(subLevel, vector(motion.velocity()), vector(motion.angularVelocity()));
        pipeline.wakeUp(subLevel);
        subLevel.updateLastPose();
        subLevel.updateBoundingBox();
        previousHandoffY = motion.center().y;
        // A high-altitude handoff can happen far from every player. Let Sable keep its flight and terrain loaded.
        if (isInSpace() || ship.isBenchmark() || motion.center().y >= subLevel.getLevel().getMaxBuildHeight()) {
            body.container().addForceLoadTicket(subLevel, HANDOFF_TICKET, Unit.INSTANCE);
        } else {
            body.container().removeForceLoadTicket(subLevel, HANDOFF_TICKET, Unit.INSTANCE);
        }
    }

    Vec3 seat() {
        return state.seat();
    }

    ShipPropellantData savePropellant() {
        return state.savePropellant();
    }

    Propellant propellant() {
        return new Propellant(state.remainingFuel(), state.remainingElectric());
    }

    boolean isDebris() {
        return state.isDebris();
    }

    BitSet damagedParts() {
        return state.damagedParts();
    }

    void updateInput(int keys, long gameTime) {
        pressedKeys |= keys & ~this.keys;
        this.keys = keys;
        inputExpiresAt = gameTime + INPUT_TIMEOUT;
    }

    void clearInput() {
        keys = 0;
        pressedKeys = 0;
        controls.clearPilot();
        inputExpiresAt = Long.MIN_VALUE;
    }

    void tickControls() {
        if (!isActive()) {
            return;
        }
        checkRemoteControl();
        if (!(ship.getFirstPassenger() instanceof ServerPlayer pilot) || pilot.isSpectator()
                || !pilot.isAlive()) {
            clearInput();
        } else if (ship.level().getGameTime() >= inputExpiresAt) {
            keys &= STAGE | CYCLE_ATTITUDE | CYCLE_THROTTLE | TOGGLE_RCS;
            pressedKeys = 0;
            controls.clearPilot();
        } else {
            if ((pressedKeys & CYCLE_ATTITUDE) != 0) {
                var modes = AttitudeMode.values();
                setAttitudeMode(modes[(controls.attitudeMode().ordinal() + 1) % modes.length]);
            }
            if ((pressedKeys & CYCLE_THROTTLE) != 0) {
                switch (controls.throttleMode()) {
                    case MANUAL, DIRECT -> setThrottle(ThrottleMode.VERTICAL_SPEED, motion().velocity().y);
                    case VERTICAL_SPEED -> setThrottle(ThrottleMode.THRUST_TO_WEIGHT, telemetry().thrustToWeight());
                    case THRUST_TO_WEIGHT -> setThrottle(ThrottleMode.MANUAL, 0);
                }
            }
            if ((pressedKeys & TOGGLE_RCS) != 0) {
                rcsEnabled = !rcsEnabled;
            }
            controls.tickPilot(keys | (pressedKeys & CUTOFF));
            stageRequested |= (pressedKeys & STAGE) != 0;
            pressedKeys = 0;
        }
        updateFlightEvents();
        syncStatus();
        if (stageRequested && !flights.frozenForWarp) {
            stageRequested = false;
            ShipFlightEvent event;
            try {
                event = advanceStage();
            } catch (RuntimeException error) {
                LOGGER.warn("Could not advance stage for {}", ship.getUUID(), error);
                if (ship.getFirstPassenger() instanceof ServerPlayer pilot) {
                    pilot.displayClientMessage(Component.translatable("flight.blockspaceprogram.stage_failed"), true);
                }
                return;
            }
            if (event != null) {
                NeoForge.EVENT_BUS.post(event);
            }
        }
    }

    void syncStatus() {
        ship.setFlightStatus((float) state.remainingFuel(), state.remainingElectric(), state.stage(), rcsEnabled);
        ship.setParachuteStates(state.parachutes().snapshot());
        if (ship.level().getGameTime() % 4 == 0 || ship.reentryHeatRatio() == 0) {
            ship.setReentryStatus(state.reentryHeatRatio(), reentryWindward);
        }
    }

    @Override
    public Telemetry telemetry() {
        var subLevel = body.subLevel();
        var level = subLevel.getLevel();
        var planet = PlanetFlightProperties.in(level);
        var motion = motion();
        double y = motion.center().y;
        double gravity = gravityAt(planet, subLevel.logicalPose().position()).length();
        double mass = subLevel.getMassTracker().getMass();
        var attitude = Attitude.fromOrientation(motion.orientation());
        return new Telemetry(ship.getUUID(), level.dimension().location(), motion.center(), motion.velocity(),
                y - (planet == null ? 0 : planet.seaLevel()), attitude, motion.angularVelocity(),
                mass, state.remainingFuel(), state.remainingElectric(), thrust, state.availableThrust().length(), gravity,
                gravity > 0 ? thrust / (mass * gravity) : 0, controls.throttle(), state.stage(),
                controls.attitudeMode(), controls.throttleMode(), vec(controls.axes()), landed, planet);
    }

    @Override
    public void setThrottle(ThrottleMode mode, double target) {
        controls.setThrottle(mode, target);
    }

    @Override
    public void setAxes(double pitch, double yaw, double roll) {
        controls.setAxes(pitch, yaw, roll);
    }

    @Override
    public void setAttitudeMode(AttitudeMode mode) {
        controls.setAttitudeMode(mode, controlOrientation());
    }

    @Override
    public void holdAttitude(double pitch, double yaw, double roll) {
        controls.setAttitudeMode(AttitudeMode.HOLD, ShipFlightControls.heading(pitch, yaw, roll));
    }

    @Override
    public boolean requestStage() {
        if (ship.dockingState().nextStage(state.stage()) >= ship.design().stageCount()) {
            return false;
        }
        stageRequested = true;
        return true;
    }

    @Override
    public RemoteResult claimRemote(BooleanSupplier connection) {
        checkRemoteControl();
        if (controls.remote() == connection) {
            return RemoteResult.CONNECTED;
        }
        if (controls.remote() != null) {
            return RemoteResult.BUSY;
        }
        if (!hasRemoteTicketCapacity()) {
            return RemoteResult.TICKET_LIMIT;
        }
        controls.connect(connection);
        setRemoteTicket(true);
        return RemoteResult.CONNECTED;
    }

    @Override
    public boolean releaseRemote(BooleanSupplier connection) {
        if (!controls.releaseRemote(connection, controlOrientation())) {
            return false;
        }
        setRemoteTicket(false);
        return true;
    }

    private boolean hasRemoteTicketCapacity() {
        return flights.active.values().stream().filter(flight -> flight.remoteTicket).count()
                < ShipServerConfig.MAX_REMOTE_SHIPS.get();
    }

    private void checkRemoteControl() {
        if (controls.checkConnection(controlOrientation())) {
            setRemoteTicket(false);
        }
    }

    private void setRemoteTicket(boolean active) {
        if (remoteTicket == active) {
            return;
        }
        if (active) {
            body.container().addForceLoadTicket(body.subLevel(), REMOTE_TICKET, Unit.INSTANCE);
        } else {
            body.container().removeForceLoadTicket(body.subLevel(), REMOTE_TICKET, Unit.INSTANCE);
        }
        remoteTicket = active;
    }

    private void updateFlightEvents() {
        if (electricDepleted) {
            electricDepleted = false;
            postFlightEvent(ShipFlightEvent.Kind.ELECTRIC_DEPLETED, state.stage());
        }
        boolean debris = isDebris();
        if (debris && state.parachutes().mounts().isEmpty()) {
            return;
        }
        if (fuelDepleted && !debris) {
            fuelDepleted = false;
            postFlightEvent(ShipFlightEvent.Kind.FUEL_DEPLETED, state.stage());
        }
        boolean supported = ship.level().getGameTime() - LANDING_CONTACT_TICKS <= supportAt;
        double speedSquared = body.pipeline().getLinearVelocity(body.subLevel(), new Vector3d()).lengthSquared();
        if (!supported && speedSquared > LANDING_SPEED_SQUARED) {
            airborne = true;
            landed = false;
        } else if (supported && speedSquared <= LANDING_SPEED_SQUARED) {
            landed = true;
            if (airborne) {
                airborne = false;
                state.parachutes().recover();
                if (!debris) {
                    postFlightEvent(ShipFlightEvent.Kind.LANDED, state.stage());
                }
            }
        }
    }

    private void postFlightEvent(ShipFlightEvent.Kind kind, int stage) {
        NeoForge.EVENT_BUS.post(new ShipFlightEvent(ship, kind, stage));
    }

    private static void checkDocking(ServerLevel level) {
        var flights = LEVELS.get(level);
        if (flights == null) {
            return;
        }
        double distance = ShipServerConfig.DOCK_DISTANCE.get();
        var limits = CaptureLimits.of(distance, ShipServerConfig.DOCK_SPEED.get(), ShipServerConfig.DOCK_ANGLE.get());
        var candidates = new ArrayList<DockingShip>();
        for (var flight : flights.active.values()) {
            if (flight.isActive()) {
                var ship = flight.dockingCandidate();
                if (ship != null) {
                    candidates.add(ship);
                }
            }
        }
        if (!flights.rejectedDocking.isEmpty()) {
            var byFlight = new IdentityHashMap<SableShipFlight, DockingShip>();
            candidates.forEach(candidate -> byFlight.put(candidate.flight, candidate));
            flights.rejectedDocking.keySet().removeIf(contact -> !contact.isAligned(byFlight, limits));
        }
        candidates.sort(Comparator.comparing((DockingShip candidate) -> candidate.flight.ship.getUUID()));
        for (int i = 0; i < candidates.size(); i++) {
            var first = candidates.get(i);
            for (int j = i + 1; j < candidates.size(); j++) {
                var second = candidates.get(j);
                if (!first.bounds.inflate(distance).intersects(second.bounds)) {
                    continue;
                }
                boolean firstPrimary = first.flight.hasPilot() == second.flight.hasPilot()
                        ? first.flight.mass() >= second.flight.mass() : first.flight.hasPilot();
                var primary = firstPrimary ? first : second;
                var incoming = firstPrimary ? second : first;
                if (primary.flight.tryDock(primary, incoming, limits)) {
                    return;
                }
            }
        }
    }

    @Nullable
    private DockingShip dockingCandidate() {
        ShipDesign design = ship.design();
        var released = ship.dockingState().released();
        ShipDesign closed = ShipDocking.closeAlignedPorts(design, state.damagedParts(), released);
        if (closed != design && !closed.equals(overBudgetClosure)) {
            if (closed.flightLayoutBytes(ship.registryAccess()) <= ShipDesign.MAX_FLIGHT_LAYOUT_BYTES) {
                updateGraph(closed, state.stage(), ship.dockingState());
                design = closed;
            } else {
                overBudgetClosure = closed;
            }
        }
        var ports = ShipDocking.openPorts(design, state.damagedParts(), released);
        if (ports.isEmpty()) {
            return null;
        }
        var motion = motion();
        return new DockingShip(this, design, motion, worldBounds(), ports.stream().map(port -> {
            Vec3 position = worldPoint(port.center());
            return new WorldPort(port, position, motion.velocity().add(motion.angularVelocity().cross(position.subtract(motion.center()))));
        }).toList());
    }

    private boolean tryDock(DockingShip primary, DockingShip incoming, CaptureLimits limits) {
        var rejection = dockingCountLimit(primary.layout, incoming.layout);
        var relative = primary.relativeOrientation(incoming);
        for (var first : primary.ports) {
            for (var second : incoming.ports) {
                var frame = ShipDocking.capture(first.port, second.port, relative, second.position.subtract(first.position),
                        second.velocity.subtract(first.velocity), limits);
                if (frame == null) {
                    continue;
                }
                var contact = DockingContact.of(this, first.port.key(), incoming.flight, second.port.key());
                if (rejection != null) {
                    rejectDocking(primary, incoming, contact, rejection, null);
                    continue;
                }
                try {
                    if (dock(primary, incoming, frame, first.port, second.port, contact)) {
                        return true;
                    }
                } catch (RuntimeException error) {
                    LOGGER.warn("Could not dock ships {} and {}", ship.getUUID(), incoming.flight.ship.getUUID(), error);
                    return false;
                }
            }
        }
        return false;
    }

    @Nullable
    private static Component dockingCountLimit(ShipDesign primary, ShipDesign incoming) {
        int parts = primary.partCount() + incoming.partCount();
        int maxParts = ShipServerConfig.MAX_MERGED_PARTS.get();
        if (parts > maxParts) {
            return Component.translatable("docking.blockspaceprogram.parts_limit", parts, maxParts);
        }
        int hulls = primary.nodes().size() + incoming.nodes().size() - parts;
        int maxHulls = ShipServerConfig.MAX_MERGED_HULL_NODES.get();
        if (hulls > maxHulls) {
            return Component.translatable("docking.blockspaceprogram.hulls_limit", hulls, maxHulls);
        }
        int stages = primary.stageCount() + incoming.stageCount();
        int maxStages = ShipServerConfig.MAX_MERGED_STAGES.get();
        return stages > maxStages ? Component.translatable("docking.blockspaceprogram.stages_limit", stages, maxStages) : null;
    }

    private void rejectDocking(DockingShip primary, DockingShip incoming, DockingContact contact, Component message,
            @Nullable DockingLayoutKey layout) {
        boolean alreadyWarned = flights.rejectedDocking.containsKey(contact);
        flights.rejectedDocking.put(contact, layout);
        if (!alreadyWarned) {
            for (var candidate : List.of(primary, incoming)) {
                candidate.flight.riders().forEach(rider -> rider.player().displayClientMessage(message, true));
                candidate.flight.hullRiders(candidate.layout).forEach(rider -> rider.player().displayClientMessage(message, true));
            }
        }
    }

    private boolean dock(DockingShip primary, DockingShip incoming, PartPose frame, Port first, Port second,
            DockingContact contact) {
        var other = incoming.flight;
        var level = body.subLevel().getLevel();
        var ownDocking = ship.dockingState();
        var peerDocking = other.ship.dockingState();
        var damage = state.dockedDamage(other.state);
        var key = new DockingLayoutKey(primary.layout, incoming.layout, frame, damage, ownDocking.released(), peerDocking.released());
        if (key.equals(flights.rejectedDocking.get(contact))) {
            return false;
        }
        int offset = primary.layout.nodes().size();
        var layout = ShipDocking.merge(primary.layout, incoming.layout, frame, first, second);
        if (layout == null || !layout.isValidLayout() || !fitsDockedLayout(layout, offset, other)) {
            return false;
        }
        var ownState = ownDocking.snapshot(primary.layout, state.stage(), ship.dockingIdentity());
        var peerState = peerDocking.snapshot(incoming.layout, other.state.stage(), other.ship.dockingIdentity());
        var docking = ownState.merge(peerState, offset);
        layout = ShipDocking.closeAlignedPorts(layout, damage, docking.released());
        int bytes = layout.flightLayoutBytes(level.registryAccess());
        if (bytes > ShipDesign.MAX_FLIGHT_LAYOUT_BYTES) {
            rejectDocking(primary, incoming, contact, Component.translatable("docking.blockspaceprogram.layout_limit",
                    bytes, ShipDesign.MAX_FLIGHT_LAYOUT_BYTES), key);
            return false;
        }
        var next = spawn(ship, level, layout, state.docked(layout, other.state, damage), vector(worldPoint(Vec3.ZERO)),
                primary.motion.orientation());
        try {
            var subLevel = next.body.subLevel();
            var center = subLevel.logicalPose().position();
            var total = momentum(primary.motion, center).add(other.momentum(incoming.motion, center));
            var mass = subLevel.getMassTracker();
            next.setMotion(new Motion(vec(center), vec(total.velocity(mass.getMass())), primary.motion.orientation(),
                    vec(total.spin(mass.getInverseInertiaTensor(), primary.motion.orientation()))));
        } catch (RuntimeException error) {
            next.removeBodyNow();
            throw error;
        }

        next.keys = keys;
        next.pressedKeys = pressedKeys;
        next.stageRequested = stageRequested;
        next.inputExpiresAt = inputExpiresAt;
        next.airborne = airborne;
        next.landed = landed;
        var existing = riders();
        var standing = hullRiders(primary.layout);
        var incomingStanding = other.hullRiders(incoming.layout);
        ship.useFlightFragment(layout, next);
        ship.setDockingState(docking);
        detach();
        next.attach();
        existing.forEach(rider -> next.moveRider(rider.player(), rider.pod()));
        other.riders().forEach(rider -> next.moveRider(rider.player(), rider.pod() + offset));
        standing.forEach(rider -> next.moveHullRider(rider.player(), rider.local()));
        incomingStanding.forEach(rider -> next.moveHullRider(rider.player(), frame.point(rider.local())));
        other.removeForOrbit();
        removeBodyNow();
        return true;
    }

    private boolean fitsDockedLayout(ShipDesign layout, int offset, SableShipFlight other) {
        var level = body.subLevel().getLevel();
        for (var node : layout.nodes().subList(offset, layout.nodes().size())) {
            var local = node.bounds();
            Vec3 center = local.getCenter();
            AABB world = new AABB(worldPoint(center), worldPoint(center));
            for (double x : new double[]{local.minX, local.maxX}) {
                for (double y : new double[]{local.minY, local.maxY}) {
                    for (double z : new double[]{local.minZ, local.maxZ}) {
                        Vec3 corner = worldPoint(new Vec3(x, y, z));
                        world = world.minmax(new AABB(corner, corner));
                    }
                }
            }
            // Ports can touch other colliders at their surfaces.
            AABB interior = world.deflate(1.0e-6);
            if (!level.getWorldBorder().isWithinBounds(world) || world.minY < level.getMinBuildHeight()
                    || level.getBlockCollisions(null, interior).iterator().hasNext()) {
                return false;
            }
            for (var neighbor : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(interior))) {
                if (neighbor != body.subLevel() && neighbor != other.body.subLevel() && !neighbor.isRemoved()
                        && neighbor.boundingBox().toMojang().intersects(interior)) {
                    return false;
                }
            }
        }
        return true;
    }

    ShipDesign layout() {
        return ship.design();
    }

    void armDocking() {
        ship.setDockingState(new ShipDockingState(ship.dockingState().stages(), Set.of()));
    }

    boolean releaseDocking(int index) {
        var layout = layout();
        if (index < 0 || index >= layout.links().size() || !layout.links().get(index).docking()) {
            return false;
        }
        var cuts = Set.of(index);
        var components = layout.components(cuts);
        var link = layout.links().get(index);
        int pilotPod = hasPilot() ? ship.assignedSeat(ship.getFirstPassenger()) : 0;
        var pilotComponent = components.stream().filter(parts -> parts.contains(pilotPod)).findFirst().orElseThrow();
        var endpoint = pilotComponent.contains(link.first().node()) ? link.first() : link.second();
        var docking = ship.dockingState().release(new PortKey(endpoint.node(), endpoint.marker()));
        cutLinks(layout, cuts, components, state.stage(), docking);
        return true;
    }

    private void updateGraph(ShipDesign updated, int stage, ShipDockingState docking) {
        state = state.withGraph(updated, stage);
        ship.setFlightDesign(updated);
        ship.setDockingState(docking);
        syncStatus();
    }

    @Nullable
    private ShipFlightEvent advanceStage() {
        ShipDesign design = ship.design();
        int stage = ship.dockingState().nextStage(state.stage());
        if (stage >= design.stageCount() || body.subLevel().isRemoved()) {
            return null;
        }
        Set<Integer> cuts = new HashSet<>();
        for (int index = 0; index < design.links().size(); index++) {
            var link = design.links().get(index);
            if (link.separable() && link.separationStage() == stage) {
                cuts.add(index);
            }
        }
        List<UUID> separatedShipIds = List.of();
        if (cuts.isEmpty()) {
            state.setStage(stage);
            syncStatus();
        } else {
            separatedShipIds = cutLinks(design, cuts, design.components(cuts), stage, ship.dockingState());
        }
        return new ShipFlightEvent(ship, ShipFlightEvent.Kind.STAGE_CHANGED, ship.activeStage(), separatedShipIds);
    }

    private List<UUID> cutLinks(ShipDesign design, Set<Integer> cuts, List<List<Integer>> components, int stage,
            ShipDockingState docking) {
        if (components.size() == 1) {
            updateGraph(design.withoutLinks(cuts), stage, docking);
            return List.of();
        }
        var level = body.subLevel().getLevel();
        var previous = motion();
        var angular = vector(previous.angularVelocity());
        var riders = riders();
        var standing = hullRiders(design);
        int[] componentForPart = new int[design.nodes().size()];
        List<Fragment> fragments = new ArrayList<>(components.size());
        try {
            for (int index = 0; index < components.size(); index++) {
                var members = new ArrayList<>(components.get(index));
                for (int part : members) {
                    componentForPart[part] = index;
                }
                var original = docking.rootIn(members);
                int root = original == null ? members.getFirst() : original.root();
                members.remove(Integer.valueOf(root));
                members.addFirst(root);
                var owner = index == 0 ? ship : new AssembledShipEntity(BlockSpaceProgram.ASSEMBLED_SHIP.get(), level);
                if (index != 0) {
                    if (original != null && original.identity().isPresent()) {
                        owner.restoreDockingIdentity(original.identity().get());
                    } else {
                        owner.inheritOwner(ship);
                    }
                }
                // A hull fragment stays on the block grid even if its root pod is tilted or off-grid.
                boolean hasHull = members.stream().anyMatch(member -> design.nodes().get(member).hull() != null);
                boolean keepOrigin = index == 0 && (!design.hasHull() || hasHull);
                var frame = original != null && !hasHull ? design.nodes().get(root).pose() : keepOrigin ? PartPose.ROOT
                        : new PartPose(BlockPos.containing(design.nodes().get(root).bounds().getCenter()).multiply(4),
                                PartPose.ROOT.rotation());
                int[] indices = ShipDesign.indices(design.nodes().size(), members);
                var projection = docking.project(indices, stage, design.stageCount());
                var layout = design.subset(members, cuts, frame, projection.stageCount(), projection::mapStage);
                var orientation = new Quaterniond(previous.orientation())
                        .mul(new Quaterniond(PartPose.orientation(frame.rotation()))).normalize();
                var next = spawn(owner, level, layout, state.fragment(layout, members, indices, projection.stage()),
                        vector(worldPoint(frame.position())), orientation);
                next.airborne = airborne;
                fragments.add(new Fragment(next, layout, indices, projection.state(), frame));
                var center = next.body.subLevel().logicalPose().position();
                var offset = center.sub(vector(previous.center()), new Vector3d());
                var linear = angular.cross(offset, new Vector3d()).add(vector(previous.velocity()));
                next.setMotion(new Motion(vec(center), vec(linear), orientation, previous.angularVelocity()));
            }
            for (int index : cuts) {
                var link = design.links().get(index);
                int first = componentForPart[link.first().node()];
                int second = componentForPart[link.second().node()];
                if (first != second) {
                    var node = design.nodes().get(link.first().node());
                    var normal = node.pose().rotation().rotate(link.first().normal());
                    var from = fragments.get(first);
                    var to = fragments.get(second);
                    double impulse = SEPARATION_SPEED / (1 / from.flight.mass() + 1 / to.flight.mass());
                    var push = previous.orientation().transform(new Vector3d(normal.getStepX(), normal.getStepY(), normal.getStepZ()))
                            .mul(impulse);
                    var anchor = worldPoint(node.point(link.first().anchor()));
                    from.flight.push(anchor, push.negate(new Vector3d()));
                    to.flight.push(anchor, push);
                }
            }
            for (int index = 1; index < fragments.size(); index++) {
                var fragment = fragments.get(index);
                var owner = fragment.flight.ship;
                owner.useFlightFragment(fragment.layout, fragment.flight);
                owner.setDockingState(fragment.docking);
                if (!level.addFreshEntity(owner)) {
                    throw new IllegalStateException("Unable to spawn separated ship");
                }
            }
        } catch (RuntimeException error) {
            for (var fragment : fragments) {
                if (fragment.flight.ship != ship) {
                    fragment.flight.ship.discard();
                }
                fragment.flight.removeBodyNow();
            }
            throw error;
        }

        var primary = fragments.getFirst();
        primary.flight.keys = keys;
        primary.flight.inputExpiresAt = inputExpiresAt;
        primary.flight.landed = landed;
        ship.useFlightFragment(primary.layout, primary.flight);
        ship.setDockingState(primary.docking);
        detach();
        primary.flight.attach();
        for (var rider : riders) {
            var fragment = fragments.get(componentForPart[rider.pod()]);
            fragment.flight.moveRider(rider.player(), fragment.indices[rider.pod()]);
        }
        for (var rider : standing) {
            var fragment = fragments.get(componentForPart[rider.node()]);
            fragment.flight.moveHullRider(rider.player(), fragment.frame.localPoint(rider.local()));
        }
        removeBodyNow();
        cleanupDebris(level);
        return fragments.subList(1, fragments.size()).stream().map(fragment -> fragment.flight.ship.getUUID()).toList();
    }

    private void push(Vec3 worldAnchor, Vector3dc worldImpulse) {
        var subLevel = body.subLevel();
        var pose = subLevel.logicalPose();
        var mass = subLevel.getMassTracker();
        var impulse = pose.orientation().transformInverse(worldImpulse, new Vector3d());
        var arm = pose.transformPositionInverse(vector(worldAnchor)).sub(mass.getCenterOfMass());
        // Rapier installs the new mass at the first step. Convert with the available mass and inertia.
        var angular = pose.orientation().transform(mass.getInverseInertiaTensor().transform(arm.cross(impulse)));
        body.pipeline().addLinearAndAngularVelocity(subLevel, worldImpulse.mul(mass.getInverseMass(), new Vector3d()), angular);
    }

    private static void cleanupDebris(ServerLevel level) {
        var flights = LEVELS.get(level);
        if (flights == null) {
            return;
        }
        int distance = level.dimension().equals(BlockSpaceProgram.ORBITAL_SPACE) ? 0 : ShipServerConfig.DISTANCE.get();
        long lifetime = ShipServerConfig.LIFETIME.get() * 20L;
        double rangeSquared = (double) distance * distance;
        var debris = flights.active.values().stream()
                .filter(flight -> !flight.ship.isBenchmark() && flight.isDebris() && !flight.body.subLevel().isRemoved())
                .toList();
        List<SableShipFlight> remaining = new ArrayList<>();
        for (var flight : debris) {
            var center = flight.body.subLevel().logicalPose().position();
            boolean expired = lifetime > 0 && level.getGameTime() - flight.ship.debrisSince() >= lifetime;
            boolean tooFar = distance > 0 && !level.players().isEmpty() && level.players().stream().noneMatch(player -> {
                var position = Sable.HELPER.projectOutOfSubLevel(level, player.position());
                return center.distanceSquared(position.x, position.y, position.z) <= rangeSquared;
            });
            if (expired || tooFar) {
                flight.ship.discard();
            } else {
                remaining.add(flight);
            }
        }
        int excess = remaining.size() - ShipServerConfig.MAX_DEBRIS.get();
        if (excess > 0) {
            remaining.sort(Comparator.comparingLong((SableShipFlight flight) -> flight.ship.debrisSince())
                    .thenComparing(flight -> flight.ship.getUUID()));
            for (int index = 0; index < excess; index++) {
                remaining.get(index).ship.discard();
            }
        }
    }

    private Vector3dc gravityAt(@Nullable PlanetFlightProperties planet, Vector3dc position) {
        if (orbitalGravity != null) {
            return vector(orbitalGravity.apply(vec(position)));
        }
        return planet == null ? flights.baseGravity : new Vector3d(0, -planet.gravityAt(position.y()), 0);
    }

    private static double relativeDensity(ServerLevel level, @Nullable PlanetFlightProperties planet, Vector3dc position) {
        return planet == null ? Math.max(0, DimensionPhysicsData.getAirPressure(level, position))
                : planet.relativeDensityAt(position.y());
    }

    static double referenceAirDensity(ServerLevel level) {
        return relativeDensity(level, null, new Vector3d(0, level.getSeaLevel(), 0)) * STANDARD_AIR_DENSITY;
    }

    static double referenceGravity(ServerLevel level) {
        return DimensionPhysicsData.getGravity(level).length();
    }

    private void step(double seconds) {
        var subLevel = body.subLevel();
        var pipeline = body.pipeline();
        var pose = subLevel.logicalPose();
        var orientation = pose.orientation();
        checkRemoteControl();
        var profile = PlanetFlightProperties.profile(subLevel.getLevel().dimension().location());
        var planet = profile == null ? null : profile.flight();
        double pressure = relativeDensity(subLevel.getLevel(), planet, pose.position());
        double density = pressure * (planet == null ? STANDARD_AIR_DENSITY : planet.seaLevelDensity());
        var gravityVector = gravityAt(planet, pose.position());
        double gravity = gravityVector.length();
        double mass = subLevel.getMassTracker().getMass();
        var linearVelocity = pipeline.getLinearVelocity(subLevel, new Vector3d());
        var angularVelocity = pipeline.getAngularVelocity(subLevel, new Vector3d());
        var drag = new Vector3d();
        Vector3d windward = null;
        double speed = 0;
        if (density > 0 && linearVelocity.lengthSquared() > 0) {
            windward = orientation.transformInverse(linearVelocity, new Vector3d());
            speed = windward.length();
            windward.div(speed);
            double force = state.aerodynamics().dragForce(windward, density, speed, mass * speed / seconds);
            drag.set(windward).mul(-force);
        }
        double heatMultiplier = profile == null ? ReentryTemperature.DEFAULT_MULTIPLIER : profile.reentryHeatMultiplier();
        if (state.updateHeating(windward, density, speed, heatMultiplier, seconds)) {
            reentryWindward.set(windward);
        } else {
            reentryWindward.zero();
        }
        var parachuteForce = new Vector3d();
        var parachuteMoment = new Vector3d();
        stepParachutes(planet, pressure, density, seconds, linearVelocity, angularVelocity, drag.length(),
                parachuteForce, parachuteMoment);
        double hoverAcceleration = 0;
        double maxUpAcceleration = 0;
        double maxThrustToWeight = 0;
        if (controls.needsThrustEstimate()) {
            var available = orientation.transform(state.availableThrust());
            var totalDrag = drag.add(parachuteForce, new Vector3d());
            hoverAcceleration = -gravityVector.y() - orientation.transform(totalDrag).y / mass;
            maxUpAcceleration = available.y / mass;
            maxThrustToWeight = gravity > 0 ? available.length() / (mass * gravity) : 0;
        }
        var impulse = new Vector3d();
        var moment = new Vector3d();
        double throttle = controls.resolveThrottle(keys, linearVelocity.y, hoverAcceleration, maxUpAcceleration, maxThrustToWeight);
        var podRotation = pilotRotation();
        // The local physics velocity omits the orbital frame's translation around the planet.
        var attitudeVelocity = orbitalGravity == null ? linearVelocity : vector(orbitalVelocity).add(linearVelocity);
        var axes = controls.resolveAxes(keys, new Quaterniond(orientation).mul(podRotation), attitudeVelocity, angularVelocity);
        double wheelTorque = state.wheelTorque();
        var acceleration = podRotation.transform(new Vector3d(axes))
                .mul(ShipFlightControls.WHEEL_ACCELERATION / Math.max(1, axes.length()));
        var massData = subLevel.getMassTracker();
        var origin = body.plotOrigin();
        ShipRcs.Command rcs = null;
        if (rcsEnabled) {
            var center = new Vector3d(massData.getCenterOfMass()).sub(origin.getX(), origin.getY(), origin.getZ());
            var translation = podRotation.transform(new Vector3d(axis(keys, TRANSLATE_RIGHT, TRANSLATE_LEFT),
                    axis(keys, TRANSLATE_UP, TRANSLATE_DOWN), axis(keys, TRANSLATE_BACK, TRANSLATE_FORWARD)));
            var torque = massData.getInertiaTensor().transform(acceleration, new Vector3d());
            rcs = state.rcsCommand(subLevel.getLevel().getServer().getTickCount(), center, translation, torque, wheelTorque);
        }
        var rcsImpulse = new Vector3d();
        var rcsMoment = new Vector3d();
        var depletion = state.burn(throttle, Math.min(1, pressure), seconds, body, impulse, moment,
                rcs == null ? List.of() : rcs.firings(), rcsImpulse, rcsMoment);
        fuelDepleted |= depletion.fuel();
        electricDepleted |= depletion.electric();
        thrust = impulse.length() / seconds;

        massData = subLevel.getMassTracker();
        var center = new Vector3d(massData.getCenterOfMass()).sub(origin.getX(), origin.getY(), origin.getZ());
        impulse.fma(seconds, parachuteForce);
        moment.fma(seconds, parachuteMoment);
        moment.sub(center.cross(impulse, new Vector3d()));
        var wheel = acceleration;
        if (rcs != null) {
            impulse.add(rcsImpulse);
            rcsMoment.sub(center.cross(rcsImpulse, new Vector3d()));
            moment.add(rcsMoment);
            // Keep the tick's allocation while compensating for changed mass or supply within a substep.
            rcs.netTorque().mul(seconds, wheel).sub(rcsMoment).div(seconds);
        } else if (wheelTorque > 0 && acceleration.lengthSquared() > 0) {
            massData.getInertiaTensor().transform(wheel);
        }
        if (wheelTorque > 0 && wheel.lengthSquared() > 0) {
            double magnitude = wheel.length();
            if (magnitude > wheelTorque) {
                wheel.mul(wheelTorque / magnitude);
            }
            moment.fma(seconds, wheel);
        }
        boolean powered = impulse.lengthSquared() > 0 || moment.lengthSquared() > 0;
        if (orbitalGravity != null || planet != null) {
            // Apply only the difference from Sable's gravity, in the body's axes.
            var correction = gravityVector.sub(flights.baseGravity, new Vector3d());
            powered |= orbitalGravity != null && correction.lengthSquared() > 0;
            impulse.fma(massData.getMass() * seconds, orientation.transformInverse(correction));
        }
        landingGear.addImpulse(body, state.damagedParts(), center, linearVelocity, gravity, seconds, impulse, moment);
        if (windward != null) {
            double torqueTime = seconds * ShipServerConfig.AERO_TORQUE_MULTIPLIER.get();
            state.aerodynamics().addDragMoment(windward, drag, center, torqueTime, moment);
        }
        impulse.fma(seconds, drag);
        if (impulse.lengthSquared() > 0 || moment.lengthSquared() > 0) {
            pipeline.applyLinearAndAngularImpulse(subLevel, impulse, moment, powered);
        }
    }

    private void stepParachutes(@Nullable PlanetFlightProperties planet, double pressure, double density, double seconds,
            Vector3dc linearVelocity, Vector3dc angularVelocity, double bodyDrag, Vector3d force, Vector3d moment) {
        var mounts = state.parachutes().mounts();
        if (mounts.isEmpty()) {
            return;
        }
        var subLevel = body.subLevel();
        var pose = subLevel.logicalPose();
        var level = subLevel.getLevel();
        var mass = subLevel.getMassTracker();
        var origin = body.plotOrigin();
        var center = new Vector3d(mass.getCenterOfMass()).sub(origin.getX(), origin.getY(), origin.getZ());
        var velocity = pose.orientation().transformInverse(linearVelocity, new Vector3d());
        var spin = pose.orientation().transformInverse(angularVelocity, new Vector3d());
        var docking = ship.dockingState();
        for (var mount : mounts) {
            boolean armed = mount.stage >= 0 && docking.actionStage(mount.node, state.stage()) >= mount.stage;
            boolean disabled = landed || state.isDamaged(mount.node);
            var arm = mount.point.sub(center, new Vector3d());
            // Wind at the attachment includes its motion as the body rotates.
            var airVelocity = spin.cross(arm, new Vector3d()).add(velocity);
            double speed = airVelocity.length();
            double height = Double.POSITIVE_INFINITY;
            if (armed && !disabled && pressure > 0 && mount.deployment.readyForFull()) {
                var position = pose.transformPosition(mount.point.add(origin.getX(), origin.getY(), origin.getZ(), new Vector3d()));
                height = heightAboveGround(level, planet, position);
            }
            mount.deployment = mount.deployment.advance(mount.performance, armed, disabled, pressure, speed, height, seconds);
            double area = mount.deployment.dragArea(mount.performance);
            if (area > 0 && density > 0 && speed > 0) {
                var direction = airVelocity.div(speed);
                var lever = arm.cross(direction, new Vector3d());
                double inverseMassAtMount = mass.getInverseMass()
                        + lever.dot(mass.getInverseInertiaTensor().transform(new Vector3d(lever)));
                double drag = Math.min(0.5 * density * speed * speed * area, speed / (seconds * inverseMassAtMount));
                direction.mul(-drag);
                force.add(direction);
                moment.add(mount.point.cross(direction, new Vector3d()));
            }
        }
        double speed = velocity.length();
        if (speed > 0) {
            // All parachutes share the braking budget left after hull drag.
            double braking = -force.dot(velocity) / speed;
            double remaining = Math.max(0, mass.getMass() * speed / seconds - bodyDrag);
            if (braking > remaining) {
                force.mul(remaining / braking);
                moment.mul(remaining / braking);
            }
        }
    }

    private static double heightAboveGround(ServerLevel level, @Nullable PlanetFlightProperties planet, Vector3dc point) {
        BlockPos pos = BlockPos.containing(point.x(), point.y(), point.z());
        var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        double ground = chunk == null ? (planet == null ? level.getSeaLevel() : planet.seaLevel())
                : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX() & 15, pos.getZ() & 15) + 1;
        return point.y() - ground;
    }

    private record Fragment(SableShipFlight flight, ShipDesign layout, int[] indices, ShipDockingState docking, PartPose frame) {}

    private record DockingShip(SableShipFlight flight, ShipDesign layout, Motion motion, AABB bounds, List<WorldPort> ports) {
        Quaterniond relativeOrientation(DockingShip other) {
            return motion.orientation().conjugate(new Quaterniond()).mul(other.motion.orientation());
        }

        @Nullable WorldPort port(PortKey key) {
            return ports.stream().filter(port -> port.port.key().equals(key)).findFirst().orElse(null);
        }
    }

    private record WorldPort(Port port, Vec3 position, Vec3 velocity) {}

    private record DockingContact(SableShipFlight first, PortKey firstPort, SableShipFlight second, PortKey secondPort) {
        static DockingContact of(SableShipFlight first, PortKey firstPort, SableShipFlight second, PortKey secondPort) {
            return first.ship.getUUID().compareTo(second.ship.getUUID()) < 0
                    ? new DockingContact(first, firstPort, second, secondPort)
                    : new DockingContact(second, secondPort, first, firstPort);
        }

        boolean isAligned(Map<SableShipFlight, DockingShip> candidates, CaptureLimits limits) {
            var a = candidates.get(first);
            var b = candidates.get(second);
            if (a == null || b == null) {
                return false;
            }
            var start = a.port(firstPort);
            var end = b.port(secondPort);
            return start != null && end != null && ShipDocking.capture(start.port, end.port, a.relativeOrientation(b),
                    end.position.subtract(start.position), Vec3.ZERO, limits) != null;
        }
    }

    private record DockingLayoutKey(ShipDesign primary, ShipDesign incoming, PartPose frame, BitSet damage,
                                    Set<PortKey> primaryReleased, Set<PortKey> incomingReleased) {}

    private static Vec3 vec(Vector3dc value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    private static Vector3d vector(Vec3 value) {
        return new Vector3d(value.x, value.y, value.z);
    }

    public record Motion(Vec3 center, Vec3 velocity, Quaterniond orientation, Vec3 angularVelocity) {}

    private record Crew(ServerPlayer player, int pod, Vec3 position, Vec3 velocity, float yaw, float pitch, boolean standing) {}

    private record Rider(ServerPlayer player, int pod) {}

    private record HullRider(ServerPlayer player, int node, Vec3 local) {}

    private static final class Flights {
        final Vector3dc baseGravity;
        final Map<ServerSubLevel, SableShipFlight> active = new IdentityHashMap<>();
        final Map<DockingContact, DockingLayoutKey> rejectedDocking = new HashMap<>();
        boolean frozenForWarp;
        boolean pausedBeforeWarp;

        Flights(Vector3dc baseGravity) {
            this.baseGravity = baseGravity;
        }
    }
}

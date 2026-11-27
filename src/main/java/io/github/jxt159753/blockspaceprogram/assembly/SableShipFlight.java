package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSableSubLevelContainerReadyEvent;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionBody;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
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
    private final ShipFlightControls controls;
    private final ShipLandingGear landingGear;
    private final Flights flights;
    private double previousHandoffY;
    private int keys;
    private int pressedKeys;
    private boolean stageRequested;
    private long inputExpiresAt = Long.MIN_VALUE;
    private boolean remoteTicket;
    private double thrust;
    private boolean fuelDepleted;
    private long supportAt = Long.MIN_VALUE;
    private boolean airborne;
    private boolean landed;

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
            if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % 20 == 0) {
                cleanupDebris(level);
            }
        });
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
                    if (!flight.body.subLevel().isRemoved() && flight.state.applyImpactDamage(flight.body)) {
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
        if (design.nodes().stream().noneMatch(node -> node.part().properties().mass() > 0)) {
            throw new IllegalArgumentException("A flying ship needs positive dry mass");
        }
        var state = new ShipFlightState(design, new ListTag(), 0, ShipFlightState.initialStage(design));
        var origin = new Vector3d(ship.getX(), ship.getY(), ship.getZ());
        var rotation = new Quaterniond(PartPose.orientation(ship.launchRotation())).normalize();
        var flight = spawn(ship, level, design, state, origin, rotation);
        ship.setPos(flight.plotPosition());
        return flight;
    }

    private static SableShipFlight spawn(AssembledShipEntity ship, ServerLevel level, ShipDesign design,
            ShipFlightState state, Vector3dc origin, Quaterniondc orientation) {
        var body = ShipCollisionBody.spawn(level, ship.getName().getString(), state.cells(), origin, orientation);
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
            ListTag savedFuel, int savedStage) {
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
        var state = savedState(ship, design, savedFuel, savedStage);
        var body = new ShipCollisionBody(container, subLevel, origin);
        body.assignMass(state.cells());
        subLevel.updateLastPose();
        return new SableShipFlight(ship, body, state, design);
    }

    static SableShipFlight rebuild(AssembledShipEntity ship, ServerLevel level, ShipDesign design, ListTag savedFuel,
            Motion motion) {
        var state = savedState(ship, design, savedFuel, ship.activeStage());
        return spawn(ship, level, design, state, vector(motion.center()), motion.orientation());
    }

    private static ShipFlightState savedState(AssembledShipEntity ship, ShipDesign design, ListTag savedFuel, int savedStage) {
        int stage = savedStage < -1 ? ShipFlightState.initialStage(design) : Math.min(savedStage, design.stageCount() - 1);
        return new ShipFlightState(design, savedFuel, ship.damagedParts(), stage);
    }

    void attach() {
        flights.active.put(body.subLevel(), this);
        if (isInSpace()) {
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
            if (subLevel.isRemoved()) {
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

    private boolean isInSpace() {
        return ship.level().dimension().equals(BlockSpaceProgram.ORBITAL_SPACE);
    }

    public static List<AssembledShipEntity> activeShips(ServerLevel level) {
        var flights = LEVELS.get(level);
        return flights == null ? List.of() : flights.active.values().stream().filter(SableShipFlight::isActive)
                .map(flight -> flight.ship).toList();
    }

    public static void setOrbitalGravity(ServerLevel level, @Nullable UnaryOperator<Vec3> gravity) {
        var flights = LEVELS.get(level);
        if (flights != null) {
            flights.orbitalGravity = gravity;
        }
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

    public static void rebase(ServerLevel level, Vec3 displacement, Vec3 velocityChange) {
        var moving = new ArrayList<Entity>();
        for (Entity entity : level.getAllEntities()) {
            if (!entity.isPassenger() && Sable.HELPER.getContaining(entity) == null) {
                moving.add(entity);
            }
        }
        for (var ship : activeShips(level)) {
            var flight = ship.activeFlight();
            var before = flight.motion();
            flight.setMotion(new Motion(before.center().add(displacement), before.velocity().add(velocityChange),
                    before.orientation(), before.angularVelocity()));
            ship.getPassengers().forEach(ship::positionRider);
        }
        Vec3 perTick = velocityChange.scale(1.0 / 20);
        for (Entity entity : moving) {
            if (entity instanceof ServerPlayer player) {
                player.teleportRelative(displacement.x, displacement.y, displacement.z);
                setPlayerVelocity(player, player.getKnownMovement().add(perTick));
            } else {
                entity.setPos(entity.position().add(displacement));
                entity.setDeltaMovement(entity.getDeltaMovement().add(perTick));
                entity.hurtMarked = true;
            }
        }
    }

    public static Vec3 worldPosition(Entity entity) {
        return vec(Sable.HELPER.projectOutOfSubLevel(entity.level(), vector(entity.position())));
    }

    public static void clearTracking(ServerPlayer player) {
        ((EntityMovementExtension) player).sable$setTrackingSubLevel(null);
    }

    private Vec3 seatPosition(ServerPlayer player) {
        return vec(body.subLevel().logicalPose().transformPosition(vector(ship.getPassengerRidingPosition(player))));
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
        } else if (player.changeDimension(new DimensionTransition(level, position, velocity.scale(1.0 / 20),
                yaw, pitch, DimensionTransition.PLACE_PORTAL_TICKET)) == null) {
            return false;
        }
        clearTracking(player);
        setPlayerVelocity(player, velocity.scale(1.0 / 20));
        return true;
    }

    public boolean board(ServerPlayer player) {
        return player.getVehicle() == ship || ship.getFirstPassenger() == null
                && movePlayer(player, body.subLevel().getLevel(), seatPosition(player),
                        Vec3.ZERO, player.getYRot(), player.getXRot()) && mount(player);
    }

    private boolean mount(ServerPlayer player) {
        if (!player.startRiding(ship, true)) {
            return false;
        }
        ship.positionRider(player);
        return true;
    }

    /** Rebuild the body before moving its occupants, and leave the source intact if travel is rejected. */
    @Nullable
    public AssembledShipEntity transfer(ServerLevel target, Motion after, Quaterniondc rotation, boolean reentry) {
        var before = motion();
        var source = body.subLevel().getLevel();
        List<Crew> crew = source.players().stream().filter(this::carries).map(player -> {
            Vec3 position = worldPosition(player);
            return new Crew(player, player.getRootVehicle() == ship, position, velocityAt(position),
                    player.getYRot(), player.getXRot());
        }).toList();
        var replacement = AssembledShipEntity.restoreFromOrbit(target, ship.getUUID(), ship.orbitSnapshot(), after, reentry);
        if (replacement == null) {
            return null;
        }
        var next = replacement.activeFlight();
        next.keys = keys;
        next.pressedKeys = pressedKeys;
        next.stageRequested = stageRequested;
        next.controls.inheritForHandoff(controls, rotation, after.orientation());
        if (inputExpiresAt > source.getGameTime()) {
            next.inputExpiresAt = target.getGameTime() + inputExpiresAt - source.getGameTime();
        }
        var moved = new ArrayList<Crew>();
        for (var member : crew) {
            var player = member.player();
            Vec3 position = member.seated() ? next.seatPosition(player)
                    : after.center().add(vec(rotation.transform(vector(member.position().subtract(before.center())))));
            if (movePlayer(player, target, position, next.velocityAt(position), member.yaw(), member.pitch())) {
                moved.add(member);
                if (!member.seated() || next.mount(player)) {
                    continue;
                }
            }
            for (var previous : moved.reversed()) {
                if (movePlayer(previous.player(), source, previous.position(), previous.velocity(),
                        previous.yaw(), previous.pitch()) && previous.seated()) {
                    mount(previous.player());
                }
            }
            next.removeForOrbit();
            return null;
        }
        next.syncStatus();
        // A client that changed levels can reuse the source plot's network address in its new level.
        moved.forEach(member -> body.subLevel().getTrackingPlayers().remove(member.player().getUUID()));
        removeForOrbit();
        return replacement;
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
        if (isInSpace() || motion.center().y >= subLevel.getLevel().getMaxBuildHeight()) {
            body.container().addForceLoadTicket(subLevel, HANDOFF_TICKET, Unit.INSTANCE);
        } else {
            body.container().removeForceLoadTicket(subLevel, HANDOFF_TICKET, Unit.INSTANCE);
        }
    }

    Vec3 seat() {
        return state.seat();
    }

    ListTag saveFuel() {
        return state.saveFuel();
    }

    boolean isDebris() {
        return state.isDebris();
    }

    long damagedParts() {
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
            keys &= STAGE | CYCLE_ATTITUDE | CYCLE_THROTTLE;
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
            controls.tickPilot(keys | (pressedKeys & CUTOFF));
            stageRequested |= (pressedKeys & STAGE) != 0;
            pressedKeys = 0;
        }
        syncStatus();
        updateFlightEvents();
        if (stageRequested && !flights.frozenForWarp) {
            stageRequested = false;
            try {
                if (advanceStage()) {
                    postFlightEvent(ShipFlightEvent.Kind.STAGE_CHANGED, ship.activeStage());
                }
            } catch (RuntimeException error) {
                LOGGER.warn("Could not advance stage for {}", ship.getUUID(), error);
                if (ship.getFirstPassenger() instanceof ServerPlayer pilot) {
                    pilot.displayClientMessage(Component.translatable("flight.blockspaceprogram.stage_failed"), true);
                }
            }
        }
    }

    void syncStatus() {
        ship.setFlightStatus((float) state.remainingFuel(), state.stage());
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
        var angles = motion.orientation().getEulerAnglesYXZ(new Vector3d());
        var attitude = new Attitude(Math.toDegrees(angles.x), Math.toDegrees(angles.y), Math.toDegrees(angles.z));
        return new Telemetry(ship.getUUID(), level.dimension().location(), motion.center(), motion.velocity(),
                y - (planet == null ? 0 : planet.seaLevel()), attitude, motion.angularVelocity(),
                mass, state.remainingFuel(), thrust, state.availableThrust().length(), gravity,
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
        controls.setAttitudeMode(mode, body.subLevel().logicalPose().orientation());
    }

    @Override
    public void holdAttitude(double pitch, double yaw, double roll) {
        controls.setAttitudeMode(AttitudeMode.HOLD, ShipFlightControls.heading(pitch, yaw, roll));
    }

    @Override
    public boolean requestStage() {
        if (state.stage() + 1 >= ship.design().stageCount()) {
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
        long loaded = flights.active.values().stream().filter(flight -> flight.remoteTicket).count();
        if (loaded >= ShipServerConfig.MAX_REMOTE_SHIPS.get()) {
            return RemoteResult.TICKET_LIMIT;
        }
        controls.connect(connection);
        setRemoteTicket(true);
        return RemoteResult.CONNECTED;
    }

    @Override
    public boolean releaseRemote(BooleanSupplier connection) {
        if (controls.remote() != connection) {
            return false;
        }
        controls.disconnect(body.subLevel().logicalPose().orientation());
        setRemoteTicket(false);
        return true;
    }

    private void checkRemoteControl() {
        if (controls.checkConnection(body.subLevel().logicalPose().orientation())) {
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
        if (isDebris()) {
            return;
        }
        if (fuelDepleted) {
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
                postFlightEvent(ShipFlightEvent.Kind.LANDED, state.stage());
            }
        }
    }

    private void postFlightEvent(ShipFlightEvent.Kind kind, int stage) {
        NeoForge.EVENT_BUS.post(new ShipFlightEvent(ship, kind, stage));
    }

    private boolean advanceStage() {
        ShipDesign design = ship.design();
        int stage = state.stage() + 1;
        if (stage >= design.stageCount() || body.subLevel().isRemoved()) {
            return false;
        }
        Set<Integer> cuts = new HashSet<>();
        for (int index = 0; index < design.links().size(); index++) {
            var link = design.links().get(index);
            if (link.separable() && link.separationStage() == stage) {
                cuts.add(index);
            }
        }
        if (cuts.isEmpty()) {
            state.setStage(stage);
            syncStatus();
        } else {
            var components = design.components(cuts);
            if (components.size() == 1) {
                ShipDesign updated = design.withoutLinks(cuts);
                state = new ShipFlightState(updated, state.saveFuel(), state.damagedParts(), stage);
                ship.setFlightDesign(updated);
                syncStatus();
            } else {
                split(design, cuts, components, stage);
            }
        }
        return true;
    }

    private void split(ShipDesign design, Set<Integer> cuts, List<List<Integer>> components, int stage) {
        var level = body.subLevel().getLevel();
        var pose = new Pose3d(body.subLevel().logicalPose());
        var pipeline = body.pipeline();
        var linear = pipeline.getLinearVelocity(body.subLevel(), new Vector3d());
        var angular = pipeline.getAngularVelocity(body.subLevel(), new Vector3d());
        int[] componentForPart = new int[design.nodes().size()];
        List<Fragment> fragments = new ArrayList<>(components.size());
        try {
            for (int index = 0; index < components.size(); index++) {
                var members = components.get(index);
                for (int part : members) {
                    componentForPart[part] = index;
                }
                var owner = index == 0 ? ship : new AssembledShipEntity(BlockSpaceProgram.ASSEMBLED_SHIP.get(), level);
                if (index != 0) {
                    owner.inheritOwner(ship);
                }
                // Keep the layout aligned to the old collision grid and place the new entity inside its plot chunks.
                BlockPos offset = index == 0 ? BlockPos.ZERO
                        : BlockPos.containing(design.nodes().get(members.getFirst()).bounds().getCenter());
                ShipDesign layout = design.subset(members, cuts, offset);
                var plot = body.plotOrigin().offset(offset);
                var origin = pose.transformPosition(new Vector3d(plot.getX(), plot.getY(), plot.getZ()));
                var next = spawn(owner, level, layout, state.fragment(layout, members, stage), origin, pose.orientation());
                fragments.add(new Fragment(next, layout, offset));
                var offsetFromCenter = next.body.subLevel().logicalPose().position().sub(pose.position(), new Vector3d());
                pipeline.addLinearAndAngularVelocity(next.body.subLevel(), angular.cross(offsetFromCenter, new Vector3d()).add(linear),
                        angular);
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
                    double impulse = SEPARATION_SPEED / (1 / from.mass() + 1 / to.mass());
                    var push = new Vector3d(normal.getStepX(), normal.getStepY(), normal.getStepZ()).mul(impulse);
                    var anchor = node.point(link.first().anchor());
                    from.push(anchor, push.negate(new Vector3d()));
                    to.push(anchor, push);
                }
            }
            for (int index = 1; index < fragments.size(); index++) {
                var fragment = fragments.get(index);
                var owner = fragment.flight.ship;
                owner.useFlightFragment(fragment.layout, fragment.flight);
                if (!level.addFreshEntity(owner)) {
                    throw new IllegalStateException("Unable to spawn separated ship");
                }
            }
        } catch (RuntimeException error) {
            for (var fragment : fragments) {
                fragment.flight.body.remove();
            }
            throw error;
        }

        var primary = fragments.getFirst();
        primary.flight.keys = keys;
        primary.flight.inputExpiresAt = inputExpiresAt;
        primary.flight.airborne = airborne;
        primary.flight.landed = landed;
        ship.useFlightFragment(primary.layout, primary.flight);
        detach();
        primary.flight.attach();
        body.remove();
        cleanupDebris(level);
    }

    private static void cleanupDebris(ServerLevel level) {
        var flights = LEVELS.get(level);
        if (flights == null) {
            return;
        }
        int distance = level.dimension().equals(BlockSpaceProgram.ORBITAL_SPACE) ? 0 : ShipServerConfig.DISTANCE.get();
        long lifetime = ShipServerConfig.LIFETIME.get() * 20L;
        double rangeSquared = (double) distance * distance;
        var debris = flights.active.values().stream().filter(flight -> flight.isDebris() && !flight.body.subLevel().isRemoved())
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
        if (flights.orbitalGravity != null) {
            return vector(flights.orbitalGravity.apply(vec(position)));
        }
        return planet == null ? flights.baseGravity : new Vector3d(0, -planet.gravityAt(position.y()), 0);
    }

    private void step(double seconds) {
        var subLevel = body.subLevel();
        var pipeline = body.pipeline();
        var pose = subLevel.logicalPose();
        var orientation = pose.orientation();
        checkRemoteControl();
        double y = pose.position().y;
        var planet = PlanetFlightProperties.in(subLevel.getLevel());
        double pressure = planet == null ? Math.max(0, DimensionPhysicsData.getAirPressure(subLevel.getLevel(), pose.position()))
                : planet.relativeDensityAt(y);
        double density = pressure * (planet == null ? STANDARD_AIR_DENSITY : planet.seaLevelDensity());
        var gravityVector = gravityAt(planet, pose.position());
        double gravity = gravityVector.length();
        double mass = subLevel.getMassTracker().getMass();
        var linearVelocity = pipeline.getLinearVelocity(subLevel, new Vector3d());
        var drag = new Vector3d();
        if (density > 0 && linearVelocity.lengthSquared() > 0) {
            var velocity = orientation.transformInverse(linearVelocity, new Vector3d());
            double speed = velocity.length();
            velocity.div(speed);
            double force = 0.5 * density * state.dragArea(velocity) * speed * speed;
            drag.set(velocity).mul(-Math.min(force, mass * speed / seconds));
        }
        double hoverAcceleration = 0;
        double maxUpAcceleration = 0;
        double maxThrustToWeight = 0;
        if (controls.needsThrustEstimate()) {
            var available = orientation.transform(state.availableThrust());
            hoverAcceleration = -gravityVector.y() - orientation.transform(drag, new Vector3d()).y / mass;
            maxUpAcceleration = available.y / mass;
            maxThrustToWeight = gravity > 0 ? available.length() / (mass * gravity) : 0;
        }
        var impulse = new Vector3d();
        var moment = new Vector3d();
        double throttle = controls.resolveThrottle(keys, linearVelocity.y, hoverAcceleration, maxUpAcceleration, maxThrustToWeight);
        fuelDepleted |= state.burn(throttle, Math.min(1, pressure), seconds, body, impulse, moment);
        thrust = impulse.length() / seconds;

        var massData = subLevel.getMassTracker();
        var origin = body.plotOrigin();
        var center = new Vector3d(massData.getCenterOfMass()).sub(origin.getX(), origin.getY(), origin.getZ());
        moment.sub(center.cross(impulse, new Vector3d()));
        var angularVelocity = pipeline.getAngularVelocity(subLevel, new Vector3d());
        var axes = controls.resolveAxes(keys, orientation, linearVelocity, angularVelocity);
        double wheelTorque = state.wheelTorque();
        if (wheelTorque > 0 && axes.lengthSquared() > 0) {
            var wheel = new Vector3d(axes).mul(ShipFlightControls.WHEEL_ACCELERATION / Math.max(1, axes.length()));
            massData.getInertiaTensor().transform(wheel);
            double magnitude = wheel.length();
            if (magnitude > wheelTorque) {
                wheel.mul(wheelTorque / magnitude);
            }
            moment.fma(seconds, wheel);
        }
        boolean powered = impulse.lengthSquared() > 0 || moment.lengthSquared() > 0;
        if (flights.orbitalGravity != null || planet != null) {
            // Apply only the difference from Sable's gravity, in the body's axes.
            var correction = gravityVector.sub(flights.baseGravity, new Vector3d());
            powered |= flights.orbitalGravity != null && correction.lengthSquared() > 0;
            impulse.fma(massData.getMass() * seconds, orientation.transformInverse(correction));
        }
        landingGear.addImpulse(body, state.damagedParts(), center, linearVelocity, gravity, seconds, impulse, moment);
        impulse.fma(seconds, drag);
        if (impulse.lengthSquared() > 0 || moment.lengthSquared() > 0) {
            pipeline.applyLinearAndAngularImpulse(subLevel, impulse, moment, powered);
        }
    }

    private record Fragment(SableShipFlight flight, ShipDesign layout, BlockPos offset) {
        double mass() {
            return flight.body.subLevel().getMassTracker().getMass();
        }

        void push(Vec3 anchor, Vector3dc impulse) {
            var body = flight.body;
            var subLevel = body.subLevel();
            var mass = subLevel.getMassTracker();
            var orientation = subLevel.logicalPose().orientation();
            BlockPos origin = body.plotOrigin().subtract(offset);
            var arm = new Vector3d(anchor.x + origin.getX(), anchor.y + origin.getY(), anchor.z + origin.getZ())
                    .sub(mass.getCenterOfMass());
            // Rapier installs the new mass at the first step. Convert the impulse with the available mass and inertia.
            var linear = orientation.transform(new Vector3d(impulse).mul(mass.getInverseMass()));
            var angular = orientation.transform(mass.getInverseInertiaTensor().transform(arm.cross(impulse)));
            body.pipeline().addLinearAndAngularVelocity(subLevel, linear, angular);
        }
    }

    private static Vec3 vec(Vector3dc value) {
        return new Vec3(value.x(), value.y(), value.z());
    }

    private static Vector3d vector(Vec3 value) {
        return new Vector3d(value.x, value.y, value.z);
    }

    public record Motion(Vec3 center, Vec3 velocity, Quaterniond orientation, Vec3 angularVelocity) {}

    private record Crew(ServerPlayer player, boolean seated, Vec3 position, Vec3 velocity, float yaw, float pitch) {}

    private static final class Flights {
        final Vector3dc baseGravity;
        final Map<ServerSubLevel, SableShipFlight> active = new IdentityHashMap<>();
        @Nullable UnaryOperator<Vec3> orbitalGravity;
        boolean frozenForWarp;
        boolean pausedBeforeWarp;

        Flights(Vector3dc baseGravity) {
            this.baseGravity = baseGravity;
        }
    }
}

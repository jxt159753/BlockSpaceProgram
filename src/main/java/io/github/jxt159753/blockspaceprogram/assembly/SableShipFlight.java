package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
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
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import static io.github.jxt159753.blockspaceprogram.assembly.ShipFlightInput.*;

/** Binds a ship and its saved flight state to a single Sable plot. Loaded only with Sable present. */
final class SableShipFlight {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String OWNER = "BlockSpaceProgramShip";
    private static final int INPUT_TIMEOUT = 10;
    private static final int THROTTLE_STEP = 5;
    private static final double WHEEL_ACCELERATION = 1.5;
    private static final double STANDARD_AIR_DENSITY = 1.225;
    private static final double SEPARATION_SPEED = 2;
    // Keep these bounded bodies loaded until the cleanup rules have removed them.
    private static final SubLevelLoadingTicketType<Unit> DEBRIS_TICKET = SubLevelLoadingTicketType.create(
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "debris"), Unit.CODEC);
    private static final Map<ServerLevel, Flights> LEVELS = new IdentityHashMap<>();

    private final AssembledShipEntity ship;
    private final ShipCollisionBody body;
    private ShipFlightState state;
    private final ShipLandingGear landingGear;
    private final Flights flights;
    private int keys;
    private int throttle;
    private boolean stageRequested;
    private long inputExpiresAt = Long.MIN_VALUE;

    private SableShipFlight(AssembledShipEntity ship, ShipCollisionBody body, ShipFlightState state, ShipDesign design) {
        this.ship = ship;
        this.body = body;
        this.state = state;
        this.landingGear = new ShipLandingGear(design);
        this.flights = LEVELS.get(body.subLevel().getLevel());
    }

    static void register() {
        NeoForge.EVENT_BUS.addListener((ForgeSableSubLevelContainerReadyEvent event) -> {
            if (event.getLevel() instanceof ServerLevel level) {
                // The running Sable pipeline keeps the base gravity it received at initialization.
                LEVELS.put(level, new Flights(DimensionPhysicsData.getGravity(level), new IdentityHashMap<>()));
            }
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> LEVELS.remove(event.getLevel()));
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
            }
        }
    }

    static SableShipFlight launch(AssembledShipEntity ship, ServerLevel level, ShipDesign design) {
        if (design.nodes().stream().noneMatch(node -> node.part().properties().mass() > 0)) {
            throw new IllegalArgumentException("A flying ship needs positive dry mass");
        }
        var state = new ShipFlightState(design, new ListTag(), 0, ShipFlightState.initialStage(design));
        var origin = new Vector3d(ship.getX(), ship.getY(), ship.getZ());
        var flight = spawn(ship, level, design, state, origin, new Quaterniond());
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

    static boolean obstructs(ServerLevel level, AABB bounds) {
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
        int stage = savedStage < -1 ? ShipFlightState.initialStage(design) : Math.min(savedStage, design.stageCount() - 1);
        var state = new ShipFlightState(design, savedFuel, ship.damagedParts(), stage);
        var body = new ShipCollisionBody(container, subLevel, origin);
        body.assignMass(state.cells());
        subLevel.updateLastPose();
        return new SableShipFlight(ship, body, state, design);
    }

    void attach() {
        flights.active.put(body.subLevel(), this);
        if (isDebris()) {
            body.container().addForceLoadTicket(body.subLevel(), DEBRIS_TICKET, Unit.INSTANCE);
        }
    }

    void detach() {
        flights.active.remove(body.subLevel(), this);
    }

    void discard() {
        if (isDebris()) {
            body.container().removeForceLoadTicket(body.subLevel(), DEBRIS_TICKET, Unit.INSTANCE);
        }
        body.subLevel().markRemoved();
        detach();
    }

    UUID bodyId() {
        return body.subLevel().getUniqueId();
    }

    Vec3 plotPosition() {
        return Vec3.atLowerCornerOf(body.plotOrigin());
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
        stageRequested |= (keys & STAGE) != 0 && (this.keys & STAGE) == 0;
        this.keys = keys;
        inputExpiresAt = gameTime + INPUT_TIMEOUT;
    }

    void clearInput() {
        keys = 0;
        throttle = 0;
        stageRequested = false;
        inputExpiresAt = Long.MIN_VALUE;
    }

    void tickControls() {
        if (!(ship.getFirstPassenger() instanceof ServerPlayer pilot) || pilot.isSpectator()
                || !pilot.isAlive()) {
            clearInput();
        } else if (ship.level().getGameTime() >= inputExpiresAt) {
            keys &= STAGE;
            throttle = 0;
            stageRequested = false;
        } else if ((keys & CUTOFF) != 0) {
            throttle = 0;
        } else {
            throttle = Math.clamp(throttle + THROTTLE_STEP * axis(keys, THROTTLE_UP, THROTTLE_DOWN), 0, 100);
        }
        syncStatus();
        if (stageRequested) {
            stageRequested = false;
            try {
                advanceStage();
            } catch (RuntimeException error) {
                LOGGER.warn("Could not advance stage for {}", ship.getUUID(), error);
                if (ship.getFirstPassenger() instanceof ServerPlayer pilot) {
                    pilot.displayClientMessage(Component.translatable("flight.blockspaceprogram.stage_failed"), true);
                }
            }
        }
    }

    void syncStatus() {
        ship.setFlightStatus(throttle, (float) state.remainingFuel(), state.stage());
    }

    private void advanceStage() {
        ShipDesign design = ship.design();
        int stage = state.stage() + 1;
        if (stage >= design.stageCount() || body.subLevel().isRemoved()) {
            return;
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
        primary.flight.throttle = throttle;
        primary.flight.inputExpiresAt = inputExpiresAt;
        ship.useFlightFragment(primary.layout, primary.flight);
        primary.flight.attach();
        detach();
        body.remove();
        cleanupDebris(level);
    }

    private static void cleanupDebris(ServerLevel level) {
        var flights = LEVELS.get(level);
        if (flights == null) {
            return;
        }
        int distance = ShipDebrisConfig.DISTANCE.get();
        long lifetime = ShipDebrisConfig.LIFETIME.get() * 20L;
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
        int excess = remaining.size() - ShipDebrisConfig.MAX_DEBRIS.get();
        if (excess > 0) {
            remaining.sort(Comparator.comparingLong((SableShipFlight flight) -> flight.ship.debrisSince())
                    .thenComparing(flight -> flight.ship.getUUID()));
            for (int index = 0; index < excess; index++) {
                remaining.get(index).ship.discard();
            }
        }
    }

    private void step(double seconds) {
        var subLevel = body.subLevel();
        var pipeline = body.pipeline();
        var pose = subLevel.logicalPose();
        double y = pose.position().y;
        var planet = PlanetFlightProperties.in(subLevel.getLevel());
        double pressure = planet == null ? Math.max(0, DimensionPhysicsData.getAirPressure(subLevel.getLevel(), pose.position()))
                : planet.relativeDensityAt(y);
        double density = pressure * (planet == null ? STANDARD_AIR_DENSITY : planet.seaLevelDensity());
        var impulse = new Vector3d();
        var moment = new Vector3d();
        state.burn(throttle / 100.0, Math.min(1, pressure), seconds, body, impulse, moment);

        var massData = subLevel.getMassTracker();
        var origin = body.plotOrigin();
        var center = new Vector3d(massData.getCenterOfMass()).sub(origin.getX(), origin.getY(), origin.getZ());
        moment.sub(center.cross(impulse, new Vector3d()));
        var wheel = new Vector3d(axis(keys, PITCH_DOWN, PITCH_UP), axis(keys, YAW_LEFT, YAW_RIGHT),
                axis(keys, ROLL_RIGHT, ROLL_LEFT));
        double wheelTorque = state.wheelTorque();
        if (wheelTorque > 0 && wheel.lengthSquared() > 0) {
            wheel.normalize(WHEEL_ACCELERATION);
            massData.getInertiaTensor().transform(wheel);
            double magnitude = wheel.length();
            if (magnitude > wheelTorque) {
                wheel.mul(wheelTorque / magnitude);
            }
            moment.fma(seconds, wheel);
        }
        boolean powered = impulse.lengthSquared() > 0 || moment.lengthSquared() > 0;
        double mass = massData.getMass();
        var orientation = pose.orientation();
        double gravity = planet == null ? flights.baseGravity.length() : planet.gravityAt(y);
        if (planet != null) {
            // Apply only the difference from Sable's gravity, in the body's axes.
            var correction = new Vector3d(0, -gravity, 0).sub(flights.baseGravity);
            impulse.fma(mass * seconds, orientation.transformInverse(correction));
        }
        var linearVelocity = pipeline.getLinearVelocity(subLevel, new Vector3d());
        landingGear.addImpulse(body, state.damagedParts(), center, linearVelocity, gravity, seconds, impulse, moment);
        if (density > 0) {
            var velocity = orientation.transformInverse(linearVelocity);
            double speed = velocity.length();
            if (speed > 0) {
                velocity.div(speed);
                double drag = 0.5 * density * state.dragArea(velocity) * speed * speed;
                impulse.fma(-Math.min(drag * seconds, mass * speed), velocity);
            }
        }
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

    private record Flights(Vector3dc baseGravity, Map<ServerSubLevel, SableShipFlight> active) {}
}

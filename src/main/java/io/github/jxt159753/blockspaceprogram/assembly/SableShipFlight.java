package io.github.jxt159753.blockspaceprogram.assembly;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSableSubLevelContainerReadyEvent;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import io.github.jxt159753.blockspaceprogram.collision.ShipCollisionBody;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import static io.github.jxt159753.blockspaceprogram.assembly.ShipFlightInput.*;

/** Binds a ship and its saved flight state to a single Sable plot. Loaded only with Sable present. */
final class SableShipFlight {
    private static final String OWNER = "BlockSpaceProgramShip";
    private static final int INPUT_TIMEOUT = 10;
    private static final int THROTTLE_STEP = 5;
    private static final double WHEEL_ACCELERATION = 1.5;
    private static final double STANDARD_AIR_DENSITY = 1.225;
    private static final Map<ServerLevel, Flights> LEVELS = new IdentityHashMap<>();

    private final AssembledShipEntity ship;
    private final ShipCollisionBody body;
    private final ShipFlightState state;
    private final ShipLandingGear landingGear;
    private final Flights flights;
    private int keys;
    private int throttle;
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
        var state = new ShipFlightState(design, new ListTag(), 0);
        var origin = new Vector3d(ship.getX(), ship.getY(), ship.getZ());
        var body = ShipCollisionBody.spawn(level, ship.getName().getString(), state.cells(), origin, new Quaterniond());
        var tag = new CompoundTag();
        tag.putUUID(OWNER, ship.getUUID());
        body.subLevel().setUserDataTag(tag);
        ship.setPos(Vec3.atLowerCornerOf(body.plotOrigin()));
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
            ListTag savedFuel) {
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
        var state = new ShipFlightState(design, savedFuel, ship.damagedParts());
        var body = new ShipCollisionBody(container, subLevel, origin);
        body.assignMass(state.cells());
        subLevel.updateLastPose();
        ship.setDamagedParts(state.damagedParts());
        return new SableShipFlight(ship, body, state, design);
    }

    void attach() {
        flights.active.put(body.subLevel(), this);
    }

    void detach() {
        flights.active.remove(body.subLevel(), this);
    }

    void discard() {
        body.subLevel().markRemoved();
        detach();
    }

    UUID bodyId() {
        return body.subLevel().getUniqueId();
    }

    Vec3 seat() {
        return state.seat();
    }

    ListTag saveFuel() {
        return state.saveFuel();
    }

    void updateInput(int keys, long gameTime) {
        this.keys = keys;
        inputExpiresAt = gameTime + INPUT_TIMEOUT;
    }

    void clearInput() {
        keys = 0;
        throttle = 0;
        inputExpiresAt = Long.MIN_VALUE;
    }

    void tickControls(AssembledShipEntity ship) {
        if (!(ship.getFirstPassenger() instanceof ServerPlayer pilot) || pilot.isSpectator()
                || !pilot.isAlive() || ship.level().getGameTime() >= inputExpiresAt) {
            clearInput();
        } else if ((keys & CUTOFF) != 0) {
            throttle = 0;
        } else {
            throttle = Math.clamp(throttle + THROTTLE_STEP * axis(keys, THROTTLE_UP, THROTTLE_DOWN), 0, 100);
        }
        ship.setFlightStatus(throttle, (float) state.remainingFuel());
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

    private record Flights(Vector3dc baseGravity, Map<ServerSubLevel, SableShipFlight> active) {}
}

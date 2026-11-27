package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.PlanetFlightProperties;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight.Motion;
import java.util.ArrayList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.slf4j.Logger;

/** Moves ships between dimension-local Sable bodies and the shared orbit store on the server thread. */
final class ShipOrbitHandoff {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ShipOrbitHandoff() {}

    static void tick(MinecraftServer server, OrbitalSavedData data, double previousSeconds, boolean warped) {
        var system = data.system();
        var region = OrbitalRegion.get(server);
        var space = server.getLevel(BlockSpaceProgram.ORBITAL_SPACE);
        if (space != null) {
            region.tick(space, data, previousSeconds, warped);
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (level == space) {
                continue;
            }
            var body = system.findBody(level.dimension().location());
            var boundary = boundary(level, body);
            double ascentY = boundary == null ? Double.POSITIVE_INFINITY : boundary.seaLevel() + boundary.ascent();
            var ascents = SableShipFlight.takeAscents(level, ascentY);
            if (boundary == null) {
                continue;
            }
            for (var ship : ascents) {
                var flight = ship.activeFlight();
                if (flight != null && data.ship(ship.getUUID()) == null) {
                    try {
                        var motion = flight.motion();
                        if (PlanetFrame.isInProjection(body, motion.center())) {
                            var frame = PlanetFrame.atDimension(system, body, boundary.seaLevel(), motion.center(),
                                    data.orbitSeconds());
                            var orbit = fit(body, frame.toOrbit(motion.velocity()), data.orbitSeconds());
                            if (flight.carriesPlayer()) {
                                region.enter(data, ship, orbit, motion, frame.dimensionToInertialRotation());
                            } else {
                                depart(data, ship, flight, frame, motion, orbit);
                            }
                        }
                    } catch (RuntimeException error) {
                        LOGGER.warn("Could not put ship {} on orbit", ship.getUUID(), error);
                    }
                }
            }
        }
        checkDescents(server, data);
    }

    static double timeWarpRadius(MinecraftServer server, CelestialBody body) {
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, body.id()));
        var boundary = boundary(level, body);
        if (boundary != null) {
            return body.radius() + boundary.descent();
        }
        var profile = PlanetFlightProperties.profile(body.id());
        return profile != null && profile.flight().hasAtmosphere()
                ? body.radius() + profile.flight().atmosphereTop() : 0;
    }

    private static void checkDescents(MinecraftServer server, OrbitalSavedData data) {
        var system = data.system();
        double now = data.orbitSeconds();
        boolean retry = data.orbitTicks() % OrbitalSavedData.TICKS_PER_SECOND == 0;
        var landed = new ArrayList<UUID>();
        for (OrbitingShip ship : data.ships()) {
            var orbit = ship.orbit();
            var body = system.body(orbit.center());
            var elements = orbit.elements();
            // Skip orbits that never enter the descent shell before looking up the target level.
            var profile = PlanetFlightProperties.profile(body.id());
            if (profile == null || profile.handoff().isEmpty()
                    || elements.periapsis() >= body.radius() + profile.handoff().orElseThrow().descent()) {
                continue;
            }
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, body.id()));
            var boundary = boundary(level, body);
            if (boundary == null) {
                continue;
            }
            double radius = body.radius() + boundary.descent();
            double crossing = elements.inwardCrossing(body.gravitationalParameter(), radius, elements.epochSeconds());
            if (crossing > now || (!retry && crossing <= now - 1.0 / OrbitalSavedData.TICKS_PER_SECOND)) {
                continue;
            }
            try {
                var tag = ship.shipData();
                if (!tag.hasUUID("FlightBody")) {
                    continue;
                }
                // Use the boundary state even when a fast ship has passed through it during this tick.
                var state = system.relativeState(orbit, crossing);
                var frame = PlanetFrame.atOrbit(system, body, boundary.seaLevel(), state.position(), crossing);
                var motion = toDimension(ship, frame, frame.dimensionPosition(), frame.toDimensionVelocity(state.velocity()));
                checkEnergy(body, state, PlanetFrame.atDimension(system, body, boundary.seaLevel(), motion.center(), crossing)
                        .toOrbit(motion.velocity()));
                if (validPosition(level, motion.center())
                        && AssembledShipEntity.restoreFromOrbit(level, ship.id(), tag, motion, true) != null) {
                    landed.add(ship.id());
                }
            } catch (RuntimeException error) {
                LOGGER.warn("Could not return ship {} to {}", ship.id(), body.id(), error);
            }
        }
        landed.forEach(data::remove);
    }

    static void placeInOrbit(ServerLevel level, UUID id, Orbit orbit) {
        var data = OrbitalSavedData.get(level.getServer());
        var system = data.system();
        var body = system.body(orbit.center());
        var state = system.relativeState(orbit, data.orbitSeconds());
        var profile = PlanetFlightProperties.profile(body.id());
        double minimumAltitude = profile == null ? 0 : profile.handoff().map(PlanetFlightProperties.HandoffHeights::descent)
                .orElse(0.0);
        if (!(state.position().length() >= body.radius() + minimumAltitude)
                || !Double.isFinite(state.specificEnergy(body.gravitationalParameter()))) {
            throw new IllegalArgumentException("The orbit must start at or above the destination's descent altitude");
        }
        var existing = data.ship(id);
        if (existing != null) {
            // A debug placement replaces the planned path, which invalidates any queued burn.
            data.put(new OrbitingShip(id, orbit, existing.orientation(), existing.angularVelocity(), existing.shipData()));
            return;
        }
        var ship = physicalShip(level, id);
        var flight = ship.activeFlight();
        if (flight.carriesPlayer()) {
            throw new IllegalArgumentException("The ship is carrying a player");
        }
        var departure = system.body(level.dimension().location());
        var motion = flight.motion();
        var frame = PlanetFrame.atDimension(system, departure, seaLevel(departure), motion.center(), data.orbitSeconds());
        depart(data, ship, flight, frame, motion, orbit);
    }

    static void placeInDimension(ServerLevel level, UUID id, Vec3 position, Vec3 velocity) {
        if (!validPosition(level, position) || !Double.isFinite(velocity.lengthSqr())) {
            throw new IllegalArgumentException("Expected a position inside the world and a finite velocity");
        }
        var data = OrbitalSavedData.get(level.getServer());
        var existing = data.ship(id);
        if (existing == null) {
            var flight = physicalShip(level, id).activeFlight();
            var motion = flight.motion();
            flight.setMotion(new Motion(position, velocity, motion.orientation(), motion.angularVelocity()));
        } else {
            var system = data.system();
            var body = system.body(level.dimension().location());
            var frame = PlanetFrame.atDimension(system, body, seaLevel(body), position, data.orbitSeconds());
            var motion = toDimension(existing, frame, position, velocity);
            if (AssembledShipEntity.restoreFromOrbit(level, id, existing.shipData(), motion, false) == null) {
                throw new IllegalArgumentException("Unable to restore the saved ship in this dimension");
            }
            data.remove(id);
        }
    }

    private static void depart(OrbitalSavedData data, AssembledShipEntity ship, SableShipFlight flight, PlanetFrame frame,
            Motion motion, Orbit orbit) {
        var orientation = frame.dimensionToInertialRotation().mul(motion.orientation());
        var angular = frame.toInertialDirection(motion.angularVelocity());
        var snapshot = new OrbitingShip(ship.getUUID(), orbit, orientation, angular, ship.orbitSnapshot());
        data.put(snapshot);
        flight.removeForOrbit();
    }

    static boolean returnFromRegion(MinecraftServer server, OrbitalSystem system, AssembledShipEntity ship,
                                     OrbitalState absolute, double now) {
        var body = system.centerAt(absolute.position(), now);
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, body.id()));
        var boundary = boundary(level, body);
        if (boundary == null) {
            return false;
        }
        var state = absolute.subtract(system.bodyState(body.id(), now));
        if (state.position().length() > body.radius() + boundary.descent() || state.position().dot(state.velocity()) >= 0) {
            return false;
        }
        var flight = ship.activeFlight();
        var before = flight.motion();
        var frame = PlanetFrame.atOrbit(system, body, boundary.seaLevel(), state.position(), now);
        var localToInertial = OrbitalRegion.toInertialRotation();
        var motion = toDimension(frame, frame.dimensionPosition(), frame.toDimensionVelocity(state.velocity()),
                new Quaterniond(localToInertial).mul(before.orientation()), OrbitalRegion.toInertial(before.angularVelocity()));
        checkEnergy(body, state, PlanetFrame.atDimension(system, body, boundary.seaLevel(), motion.center(), now)
                .toOrbit(motion.velocity()));
        return validPosition(level, motion.center()) && flight.transfer(level, motion,
                frame.dimensionToInertialRotation().conjugate().mul(localToInertial), true) != null;
    }

    private static Motion toDimension(OrbitingShip ship, PlanetFrame frame, Vec3 position, Vec3 velocity) {
        return toDimension(frame, position, velocity, ship.orientation(), ship.angularVelocity());
    }

    private static Motion toDimension(PlanetFrame frame, Vec3 position, Vec3 velocity, Quaterniond attitude, Vec3 angular) {
        var orientation = frame.dimensionToInertialRotation().conjugate().mul(attitude).normalize();
        return new Motion(position, velocity, orientation, frame.toDimensionDirection(angular));
    }

    static Orbit fit(CelestialBody body, OrbitalState state, double seconds) {
        double mu = body.gravitationalParameter();
        var elements = OrbitElements.fromState(mu, state, seconds);
        var fitted = elements.stateAt(mu, seconds);
        checkEnergy(body, state, fitted);
        if (!(state.position().distanceTo(fitted.position()) <= 0.01)
                || !(state.velocity().distanceTo(fitted.velocity()) <= 0.001)) {
            throw new IllegalArgumentException("Orbit fitting did not preserve position and velocity");
        }
        return new Orbit(body.id(), elements);
    }

    private static void checkEnergy(CelestialBody body, OrbitalState before, OrbitalState after) {
        double mu = body.gravitationalParameter();
        double energy = before.specificEnergy(mu);
        double tolerance = Math.max(1e-4, Math.abs(energy) * 1e-9);
        if (!Double.isFinite(energy) || !(Math.abs(energy - after.specificEnergy(mu)) <= tolerance)) {
            throw new IllegalArgumentException("Handoff did not preserve specific orbital energy");
        }
    }

    private static AssembledShipEntity physicalShip(ServerLevel level, UUID id) {
        if (level.getEntity(id) instanceof AssembledShipEntity ship && ship.activeFlight() != null) {
            return ship;
        }
        throw new IllegalArgumentException("No loaded Sable ship with that UUID in this dimension");
    }

    private static double seaLevel(CelestialBody body) {
        var profile = PlanetFlightProperties.profile(body.id());
        if (profile == null) {
            throw new IllegalArgumentException("No ship flight properties for " + body.id());
        }
        return profile.flight().seaLevel();
    }

    static boolean validPosition(ServerLevel level, Vec3 position) {
        return Double.isFinite(position.lengthSqr()) && Level.isInSpawnableBounds(BlockPos.containing(position))
                && level.getWorldBorder().isWithinBounds(position);
    }

    @Nullable
    private static Boundary boundary(@Nullable ServerLevel level, @Nullable CelestialBody body) {
        if (level == null || body == null) {
            return null;
        }
        var profile = PlanetFlightProperties.profile(body.id());
        if (profile == null || profile.handoff().isEmpty()) {
            return null;
        }
        double seaLevel = profile.flight().seaLevel();
        var heights = profile.handoff().orElseThrow();
        return seaLevel + heights.descent() > level.getMaxBuildHeight()
                ? new Boundary(seaLevel, heights.ascent(), heights.descent()) : null;
    }

    private record Boundary(double seaLevel, double ascent, double descent) {}
}

package io.github.jxt159753.blockspaceprogram.orbit;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.OrbitalShipPropulsion;
import io.github.jxt159753.blockspaceprogram.assembly.PlanetFlightProperties;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.AttitudeMode;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightControl.ThrottleMode;
import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData.ManeuverResult;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/** Resolves an owned ship and its current representation on the server thread. */
public final class OrbitalFlightControl {
    private final MinecraftServer server;
    private final UUID shipId;
    private final UUID ownerId;

    public OrbitalFlightControl(MinecraftServer server, UUID shipId, UUID ownerId) {
        this.server = server;
        this.shipId = shipId;
        this.ownerId = ownerId;
    }

    @Nullable
    public Telemetry telemetry() {
        var data = OrbitalSavedData.get(server);
        var stored = onRails(data);
        if (stored != null) {
            var queued = stored.maneuver();
            var node = queued.or(() -> data.maneuverDraft(shipId));
            var orbital = orbitTelemetry(data, stored.orbit(), true, node, queued.isPresent());
            return new Telemetry(onRailsTelemetry(data, stored, orbital), orbital);
        }
        var ship = AssembledShipEntity.findOwnedFlight(server, shipId, ownerId);
        if (ship == null) {
            return null;
        }
        var orbit = ship.level().dimension().equals(BlockSpaceProgram.ORBITAL_SPACE)
                ? OrbitalRegion.get(server).orbitFor(ship, data) : null;
        return new Telemetry(ship.flightControl().telemetry(),
                orbit == null ? null : orbitTelemetry(data, orbit, false, Optional.empty(), false));
    }

    public ManeuverResult addManeuverNode(ManeuverNode node) {
        var data = OrbitalSavedData.get(server);
        var ship = onRails(data);
        return ship == null ? ManeuverResult.MISSING_SHIP : data.planManeuver(ship, node, server.registryAccess());
    }

    public ManeuverResult deleteManeuverNode() {
        var data = OrbitalSavedData.get(server);
        return onRails(data) == null ? ManeuverResult.MISSING_SHIP
                : data.commandManeuver(shipId, Optional.empty(), server.registryAccess());
    }

    public ManeuverResult executeManeuverNode() {
        var data = OrbitalSavedData.get(server);
        var ship = onRails(data);
        return ship == null ? ManeuverResult.MISSING_SHIP : data.executeManeuver(ship, server.registryAccess());
    }

    public WarpResult warpTo(double seconds) {
        if (!server.isSingleplayer() || server.isPublished()) {
            return WarpResult.MULTIPLAYER;
        }
        var owner = server.getPlayerList().getPlayer(ownerId);
        if (owner == null || !owner.isAlive() || !OrbitalTimeWarp.canUse(owner)) {
            return WarpResult.FORBIDDEN;
        }
        var space = ModList.get().isLoaded("sable") ? server.getLevel(BlockSpaceProgram.ORBITAL_SPACE) : null;
        if (space == null) {
            return WarpResult.NOT_IN_ORBIT;
        }
        var data = OrbitalSavedData.get(server);
        var region = OrbitalRegion.get(server);
        if (onRails(data) == null && !(region.reference() != null
                && space.getEntity(shipId) instanceof AssembledShipEntity ship
                && ownerId.equals(ship.owner()) && ship.flightControl() != null)) {
            return WarpResult.NOT_IN_ORBIT;
        }
        return region.timeWarp().warpTo(space, data, owner, seconds);
    }

    public boolean releaseOnRails(BooleanSupplier connection) {
        var data = OrbitalSavedData.get(server);
        var ship = onRails(data);
        var controls = data.handoffControls(shipId);
        return ship != null && controls != null && controls.releaseRemote(connection, ship.orientation());
    }

    @Nullable
    private OrbitingShip onRails(OrbitalSavedData data) {
        var ship = data.ship(shipId);
        return ship != null && ownerId.equals(ship.ownerId()) ? ship : null;
    }

    private static OrbitTelemetry orbitTelemetry(OrbitalSavedData data, Orbit orbit, boolean onRails,
            Optional<ManeuverNode> maneuver, boolean queued) {
        var system = data.system();
        var body = system.body(orbit.center());
        var elements = orbit.elements();
        double seconds = data.orbitSeconds();
        return new OrbitTelemetry(orbit, seconds, system.relativeState(orbit, seconds),
                elements.periapsis() - body.radius(), elements.apoapsis() - body.radius(),
                elements.period(body.gravitationalParameter()), onRails, maneuver, queued);
    }

    private ShipFlightControl.Telemetry onRailsTelemetry(OrbitalSavedData data, OrbitingShip ship, OrbitTelemetry orbital) {
        var body = data.system().body(ship.orbit().center());
        var state = orbital.state();
        double radius = state.position().length();
        var snapshot = OrbitalShipPropulsion.read(server.registryAccess(), ship.shipData());
        var propulsion = snapshot == null ? null : snapshot.propulsion();
        var controls = data.handoffControls(shipId);
        var orientation = ship.orientation();
        if (controls != null) {
            controls.checkConnection(orientation);
        }
        var profile = PlanetFlightProperties.profile(body.id());
        return new ShipFlightControl.Telemetry(shipId, BlockSpaceProgram.ORBITAL_SPACE.location(), state.position(),
                state.velocity(), radius - body.radius(), ShipFlightControl.Attitude.fromOrientation(orientation),
                ship.angularVelocity(), propulsion == null ? 0 : propulsion.wetMass(),
                propulsion == null ? 0 : propulsion.fuel(), 0,
                propulsion == null ? 0 : propulsion.groups().stream().mapToDouble(VacuumPropulsion.Group::thrust).sum(),
                body.gravitationalParameter() / (radius * radius), 0, 0, snapshot == null ? -1 : snapshot.stage(),
                controls == null ? AttitudeMode.MANUAL : controls.attitudeMode(),
                controls == null ? ThrottleMode.MANUAL : controls.throttleMode(), Vec3.ZERO, false,
                profile == null ? null : profile.flight(), state.position().dot(state.velocity()) / radius);
    }

    public record Telemetry(ShipFlightControl.Telemetry flight, @Nullable OrbitTelemetry orbit) {}

    /** A center-relative, inertial reading at the current shared orbital time. Apsides are altitudes in meters. */
    public record OrbitTelemetry(Orbit orbit, double seconds, OrbitalState state, double periapsis, double apoapsis,
                                 double period, boolean onRails, Optional<ManeuverNode> maneuver, boolean queued) {}

    public enum WarpResult {
        STARTED, MULTIPLAYER, FORBIDDEN, NOT_IN_ORBIT, NOT_COASTING, INVALID_TIME, BUSY
    }
}

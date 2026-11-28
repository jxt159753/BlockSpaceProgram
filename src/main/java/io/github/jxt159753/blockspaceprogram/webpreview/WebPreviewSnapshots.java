package io.github.jxt159753.blockspaceprogram.webpreview;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.PlanetFlightProperties;
import io.github.jxt159753.blockspaceprogram.assembly.ShipServerConfig.WebPreviewScope;
import io.github.jxt159753.blockspaceprogram.orbit.CelestialBody;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalRegion;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitingShip;
import io.github.jxt159753.blockspaceprogram.orbit.PlanetFrame;
import io.github.jxt159753.blockspaceprogram.webpreview.WebPreviewFeed.Body;
import io.github.jxt159753.blockspaceprogram.webpreview.WebPreviewFeed.Clock;
import io.github.jxt159753.blockspaceprogram.webpreview.WebPreviewFeed.Maneuver;
import io.github.jxt159753.blockspaceprogram.webpreview.WebPreviewFeed.Position;
import io.github.jxt159753.blockspaceprogram.webpreview.WebPreviewFeed.Ship;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/** Reads gameplay state on the server thread and projects just the public web fields. */
final class WebPreviewSnapshots {
    private final MinecraftServer server;
    private final OrbitalSavedData orbits;
    private final WebPreviewVisibility visibility;
    private final boolean showAll;
    private final boolean sable = ModList.get().isLoaded("sable");
    private final Map<UUID, Ship> ships = new HashMap<>();
    private final Set<UUID> localShips = new HashSet<>();
    private final Set<UUID> visibilityChanges = new HashSet<>();
    private final WebPreviewFeed feed;
    private long previousTick;
    private long lastLocalSample;

    WebPreviewSnapshots(MinecraftServer server, WebPreviewScope scope) {
        this.server = server;
        orbits = OrbitalSavedData.get(server);
        visibility = WebPreviewVisibility.get(server);
        showAll = scope == WebPreviewScope.ALL;
        previousTick = orbits.orbitTicks();
        for (var ship : orbits.ships()) {
            updateOrbit(ship);
        }
        sampleLocalShips(System.nanoTime());
        var bodies = orbits.system().bodies().stream().map(body -> {
            var orbit = body.orbit();
            double soi = orbits.system().sphereOfInfluence(body.id());
            return new Body(body.id().toString(), body.radius(), body.gravitationalParameter(),
                    Double.isFinite(soi) ? soi : null, orbit == null ? null : orbit.center().toString(),
                    orbit == null ? null : orbit.elements());
        }).toList();
        feed = new WebPreviewFeed(bodies, ships, clock(1));
    }

    WebPreviewFeed feed() {
        return feed;
    }

    void visibilityChanged(UUID ship) {
        if (!showAll) {
            visibilityChanges.add(ship);
        }
    }

    void tick() {
        long nextTick = orbits.orbitTicks();
        var clock = clock(nextTick - previousTick);
        previousTick = nextTick;
        boolean changed = false;
        for (UUID id : orbits.lastChanges()) {
            var ship = orbits.ship(id);
            if (ship != null) {
                localShips.remove(id);
                changed |= updateOrbit(ship);
            } else if (!localShips.contains(id)) {
                changed |= ships.remove(id) != null;
            }
        }
        for (UUID id : visibilityChanges) {
            var ship = orbits.ship(id);
            if (ship != null) {
                changed |= updateOrbit(ship);
            }
        }
        long now = System.nanoTime();
        if (!visibilityChanges.isEmpty() || now - lastLocalSample >= 1_000_000_000L) {
            changed |= sampleLocalShips(now);
            visibilityChanges.clear();
        }
        feed.publish(clock, changed ? Map.copyOf(ships) : null);
    }

    private Clock clock(long step) {
        return new Clock(orbits.orbitSeconds(),
                step * (double) server.tickRateManager().tickrate() / OrbitalSavedData.TICKS_PER_SECOND);
    }

    private boolean visible(UUID id, @Nullable UUID owner) {
        return showAll || visibility.isPublic(id, owner);
    }

    private boolean updateOrbit(OrbitingShip ship) {
        if (!visible(ship.id(), ship.ownerId())) {
            return ships.remove(ship.id()) != null;
        }
        var orbit = ship.orbit();
        var node = ship.maneuver().orElse(null);
        var maneuver = node == null ? null
                : new Maneuver(node.seconds(), node.deltaV().x, node.deltaV().y, node.deltaV().z);
        var next = new Ship(orbit.center().toString(), ship.customNameJson(), orbit.elements(), null, maneuver);
        return !next.equals(ships.put(ship.id(), next));
    }

    private boolean sampleLocalShips(long now) {
        lastLocalSample = now;
        var system = orbits.system();
        double seconds = orbits.orbitSeconds();
        var nextLocal = new HashMap<UUID, Ship>();
        for (var body : system.bodies()) {
            var profile = PlanetFlightProperties.profile(body.id());
            var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, body.id()));
            if (profile == null || level == null) {
                continue;
            }
            double seaLevel = profile.flight().seaLevel();
            for (var ship : level.getEntities(BlockSpaceProgram.ASSEMBLED_SHIP.get(), this::includeLocal)) {
                Vec3 location = ship.dimensionCenter();
                Vec3 position = PlanetFrame.atDimension(system, body, seaLevel, location, seconds).relativePosition();
                nextLocal.put(ship.getUUID(), localShip(ship, body, position, location.y - seaLevel));
            }
        }
        var space = sable ? server.getLevel(BlockSpaceProgram.ORBITAL_SPACE) : null;
        if (space != null) {
            for (var contact : OrbitalRegion.shipContacts(server, orbits)) {
                if (space.getEntity(contact.id()) instanceof AssembledShipEntity ship && includeLocal(ship)) {
                    Vec3 absolute = contact.state().position();
                    var center = system.centerAt(absolute, seconds);
                    Vec3 relative = absolute.subtract(system.bodyState(center.id(), seconds).position());
                    nextLocal.put(contact.id(), localShip(ship, center, relative, relative.length() - center.radius()));
                }
            }
        }
        boolean changed = false;
        for (UUID id : localShips) {
            if (!nextLocal.containsKey(id)) {
                changed |= ships.remove(id) != null;
            }
        }
        for (var entry : nextLocal.entrySet()) {
            changed |= !entry.getValue().equals(ships.put(entry.getKey(), entry.getValue()));
        }
        localShips.clear();
        localShips.addAll(nextLocal.keySet());
        return changed;
    }

    private boolean includeLocal(AssembledShipEntity ship) {
        return ship.isAlive() && !ship.isBenchmark() && orbits.ship(ship.getUUID()) == null
                && visible(ship.getUUID(), ship.owner());
    }

    private Ship localShip(AssembledShipEntity ship, CelestialBody body, Vec3 position, double altitude) {
        var component = ship.getCustomName();
        String name = component == null ? null : Component.Serializer.toJson(component, server.registryAccess());
        return new Ship(body.id().toString(), name, null,
                new Position(position.x, position.y, position.z, altitude), null);
    }
}

package io.github.jxt159753.blockspaceprogram.orbit;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.OrbitalShipPropulsion;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight;
import io.github.jxt159753.blockspaceprogram.assembly.ShipServerConfig;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalFlightControl.WarpResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleFunction;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

/** Session-only warp rate for the private integrated server. */
final class OrbitalTimeWarp {
    private static final int[] RATES = { 1, 5, 10, 50, 100, 1_000, 10_000, 100_000 };
    private static final String MESSAGE = "orbit.blockspaceprogram.warp.";
    private int gear;
    @Nullable private Target target;

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("bsp_warp")
                .requires(source -> source.getEntity() instanceof ServerPlayer player && canUse(player))
                .executes(context -> changeRate(context.getSource().getPlayerOrException(), 0))
                .then(Commands.literal("faster")
                        .executes(context -> changeRate(context.getSource().getPlayerOrException(), 1)))
                .then(Commands.literal("slower")
                        .executes(context -> changeRate(context.getSource().getPlayerOrException(), -1)))
                .then(Commands.literal("stop")
                        .executes(context -> changeRate(context.getSource().getPlayerOrException(), -RATES.length))));
    }

    static boolean canUse(ServerPlayer player) {
        var server = player.serverLevel().getServer();
        return isPrivateWorld(server) && server.isSingleplayerOwner(player.getGameProfile());
    }

    static boolean isPrivateWorld(MinecraftServer server) {
        return server.isSingleplayer() && !server.isPublished();
    }

    private static int changeRate(ServerPlayer player, int change) {
        var server = player.serverLevel().getServer();
        var space = server.getLevel(BlockSpaceProgram.ORBITAL_SPACE);
        var region = OrbitalRegion.get(server);
        if (region.benchmarking()) {
            player.sendSystemMessage(BenchmarkReport.text("orbital_busy"));
            return 0;
        }
        var warp = region.timeWarp();
        if (change > 0) {
            if (space == null || !player.isAlive() || OrbitalRegion.referenceFor(player) == null) {
                player.displayClientMessage(Component.translatable(MESSAGE + "in_orbit"), true);
                return 0;
            }
            if (!SableShipFlight.isCoasting(space)) {
                player.displayClientMessage(Component.translatable(MESSAGE + "coast"), true);
                return 0;
            }
        }
        if (space != null) {
            if (change != 0) {
                warp.target = null;
            }
            warp.setGear(space, Math.clamp(warp.gear + change, 0, RATES.length - 1));
        }
        int rate = RATES[warp.gear];
        player.displayClientMessage(Component.translatable(MESSAGE + "rate", rate), true);
        return rate;
    }

    WarpResult warpTo(ServerLevel space, OrbitalSavedData data, ServerPlayer owner, double seconds) {
        double tick = Math.ceil(seconds * OrbitalSavedData.TICKS_PER_SECOND);
        if (!(seconds > data.orbitSeconds() && tick > data.orbitTicks() && tick < Long.MAX_VALUE)) {
            return WarpResult.INVALID_TIME;
        }
        if (OrbitalRegion.get(space.getServer()).benchmarking()) {
            return WarpResult.BUSY;
        }
        if (!SableShipFlight.isCoasting(space)) {
            return WarpResult.NOT_COASTING;
        }
        target = new Target(owner.getUUID(), (long) tick);
        setGear(space, RATES.length - 1);
        return WarpResult.STARTED;
    }

    int nextStep(ServerLevel space, OrbitalSavedData data) {
        if (gear == 0) {
            return 1;
        }
        var server = space.getServer();
        var owner = target == null ? space.players().stream().filter(ServerPlayer::isAlive)
                .filter(OrbitalTimeWarp::canUse).findFirst().orElse(null) : server.getPlayerList().getPlayer(target.owner());
        var reference = OrbitalRegion.get(server).reference();
        if (owner == null || !owner.isAlive() || !canUse(owner) || (target == null && reference == null)) {
            reset(space);
            return 1;
        }
        if (!SableShipFlight.isCoasting(space)) {
            stop(space, owner, "burn");
            return 1;
        }

        long remaining = target == null ? Long.MAX_VALUE : target.tick() - data.orbitTicks();
        int requested = (int) Math.min(RATES[gear], remaining);
        long toTick = data.orbitTicks() + requested;
        double to = toTick / (double) OrbitalSavedData.TICKS_PER_SECOND;
        var limit = firstLimit(space, data, reference, toTick, to);
        if (limit.seconds() <= to) {
            stop(space, owner, limit.reason());
            return stepBefore(data.orbitTicks(), requested, limit.seconds());
        }
        if (requested == remaining) {
            reset(space);
        }
        return requested;
    }

    void reset(ServerLevel space) {
        target = null;
        setGear(space, 0);
    }

    private void setGear(ServerLevel space, int next) {
        if ((next == 0) != (gear == 0)) {
            SableShipFlight.freezeOrbitalPhysics(space, next > 0);
        }
        gear = next;
    }

    private void stop(ServerLevel space, ServerPlayer player, String reason) {
        reset(space);
        player.displayClientMessage(Component.translatable(MESSAGE + "stopped",
                Component.translatable(MESSAGE + reason)), true);
    }

    static int stepBefore(long now, int requested, double eventSeconds) {
        int ticksPerSecond = OrbitalSavedData.TICKS_PER_SECOND;
        long lastSafeTick = (long) (eventSeconds * ticksPerSecond);
        if (lastSafeTick / (double) ticksPerSecond >= eventSeconds) {
            lastSafeTick--;
        }
        return (int) Math.clamp(lastSafeTick - now, 1, requested);
    }

    private static Limit firstLimit(ServerLevel space, OrbitalSavedData data, @Nullable Orbit reference,
            long toTick, double to) {
        double from = data.orbitSeconds();
        var limit = new Limit(Double.POSITIVE_INFINITY, "");
        var system = data.system();
        var radii = new HashMap<ResourceLocation, Double>();
        for (var body : system.bodies()) {
            radii.put(body.id(), ShipOrbitHandoff.timeWarpRadius(space.getServer(), body));
        }
        var members = reference == null ? List.<OrbitalState>of() : OrbitalRegion.get(space.getServer()).warpMembers(space);
        var referenceLegs = reference == null ? List.<Orbit>of() : system.conics(reference, from, to);
        for (var member : members) {
            double time = firstDescent(system, referenceLegs, radii, member.position(), from, Math.min(to, limit.seconds()));
            limit = limit.earlier(time, "descent");
        }

        var forecast = new ShipForecast(system, radii, members, referenceLegs, limit);
        for (var ship : data.ships()) {
            double until = Math.min(to, forecast.limit.seconds());
            if (until <= from) {
                break;
            }
            var node = ship.maneuver().orElse(null);
            if (node != null && node.seconds() < until) {
                // The thrust path can bring the ship into the descent shell or the local physics region.
                var propulsion = OrbitalShipPropulsion.read(space.registryAccess(), ship.shipData());
                var burn = propulsion == null ? null : propulsion.propulsion().startBurn();
                long lastTick = Math.min(toTick, (long) Math.ceil(until * OrbitalSavedData.TICKS_PER_SECOND));
                ManeuverBurn.advance(system, ship, node, burn, data.orbitTicks(), lastTick, forecast::advance);
            } else {
                forecast.advance(ship.orbit(), from, until);
            }
        }
        return forecast.limit;
    }

    private record Target(UUID owner, long tick) {}

    static double firstDescent(OrbitalSystem system, List<Orbit> legs, Map<ResourceLocation, Double> radii, Vec3 offset,
                               double from, double to) {
        for (int i = 0; i < legs.size(); i++) {
            var leg = legs.get(i);
            double start = i == 0 ? from : leg.elements().epochSeconds();
            if (start > to) {
                break;
            }
            double end = i + 1 == legs.size() ? to : Math.min(to, legs.get(i + 1).elements().epochSeconds());
            double radius = radii.getOrDefault(leg.center(), 0.0);
            var elements = leg.elements();
            if (radius == 0 || elements.periapsis() - offset.length() >= radius) {
                continue;
            }
            double mu = system.body(leg.center()).gravitationalParameter();
            double time;
            if (offset.equals(Vec3.ZERO)) {
                time = elements.stateAt(mu, start).position().lengthSqr() < radius * radius
                        ? start : elements.inwardCrossing(mu, radius, start);
            } else {
                double periapsis = elements.periapsis();
                time = new Proximity(t -> {
                    var state = elements.stateAt(mu, t);
                    return new OrbitalState(state.position().add(offset), state.velocity());
                }, radius, Double.POSITIVE_INFINITY, mu / (periapsis * periapsis)).firstInside(start, end);
            }
            if (time <= end) {
                return time;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private record Limit(double seconds, String reason) {
        Limit earlier(double candidate, String why) {
            return candidate < seconds ? new Limit(candidate, why) : this;
        }
    }

    private static final class ShipForecast {
        private final OrbitalSystem system;
        private final Map<ResourceLocation, Double> radii;
        private final List<OrbitalState> members;
        @Nullable private final DoubleFunction<OrbitalState> origin;
        private final double originAcceleration;
        private final double entry = ShipServerConfig.orbitEntryDistance();
        private final double speed = ShipServerConfig.orbitEntrySpeed();
        private Limit limit;

        private ShipForecast(OrbitalSystem system, Map<ResourceLocation, Double> radii, List<OrbitalState> members,
                List<Orbit> referenceLegs, Limit limit) {
            this.system = system;
            this.radii = radii;
            this.members = members;
            this.origin = referenceLegs.isEmpty() ? null : system.forecast(referenceLegs);
            this.originAcceleration = system.maxAcceleration(referenceLegs);
            this.limit = limit;
        }

        @Nullable
        Orbit advance(Orbit orbit, double from, double to) {
            if (limit.seconds() <= from) {
                return null;
            }
            to = Math.min(to, limit.seconds());
            var legs = system.conics(orbit, from, to);
            limit = limit.earlier(firstDescent(system, legs, radii, Vec3.ZERO, from, to), "descent");
            if (origin != null && limit.seconds() > from) {
                var target = system.forecast(legs);
                double acceleration = originAcceleration + system.maxAcceleration(legs);
                for (var member : members) {
                    // The region's local positions are fixed during warp. Retain the member's own velocity for entry.
                    var contact = new Proximity(t -> target.apply(t).subtract(origin.apply(t)).subtract(member),
                            entry, speed, acceleration);
                    limit = limit.earlier(contact.firstInside(from, Math.min(to, limit.seconds())), "encounter");
                }
            }
            return limit.seconds() <= to ? null : legs.getLast();
        }
    }

    record Proximity(DoubleFunction<OrbitalState> difference, double radius, double maxSpeed, double acceleration) {
        double firstInside(double from, double to) {
            return firstInside(from, difference.apply(from), to, difference.apply(to));
        }

        private double firstInside(double from, OrbitalState start, double to, OrbitalState end) {
            double startSpeed = start.velocity().length();
            if (start.position().lengthSqr() < radius * radius && startSpeed <= maxSpeed) {
                return from;
            }
            double elapsed = to - from;
            // Acceleration bounds the velocity change from the nearer endpoint and the deviation from the chord.
            double reach = radius + acceleration * elapsed * elapsed / 8;
            if (Math.min(startSpeed, end.velocity().length()) > maxSpeed + acceleration * elapsed / 2
                    || distanceToChordSquared(start.position(), end.position()) >= reach * reach) {
                return Double.POSITIVE_INFINITY;
            }
            double mid = from + elapsed / 2;
            if (elapsed <= 1e-3 || mid == from || mid == to) {
                return from;
            }
            var sample = difference.apply(mid);
            double first = firstInside(from, start, mid, sample);
            return Double.isFinite(first) ? first : firstInside(mid, sample, to, end);
        }

        private static double distanceToChordSquared(Vec3 from, Vec3 to) {
            var chord = to.subtract(from);
            double lengthSquared = chord.lengthSqr();
            return lengthSquared == 0 ? from.lengthSqr()
                    : from.add(chord.scale(Math.clamp(-from.dot(chord) / lengthSquared, 0, 1))).lengthSqr();
        }
    }
}

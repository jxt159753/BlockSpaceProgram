package io.github.jxt159753.blockspaceprogram.orbit;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight;
import io.github.jxt159753.blockspaceprogram.assembly.ShipServerConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleFunction;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Session-only warp rate for the private integrated server. */
final class OrbitalTimeWarp {
    private static final int[] RATES = { 1, 5, 10, 50, 100, 1_000, 10_000, 100_000 };
    private static final OrbitalSystem SYSTEM = OrbitalSystem.M3;
    private static final String MESSAGE = "orbit.blockspaceprogram.warp.";
    private int gear;

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

    private static boolean canUse(ServerPlayer player) {
        var server = player.serverLevel().getServer();
        return server.isSingleplayer() && !server.isPublished() && server.isSingleplayerOwner(player.getGameProfile());
    }

    private static int changeRate(ServerPlayer player, int change) {
        var server = player.serverLevel().getServer();
        var space = server.getLevel(BlockSpaceProgram.ORBITAL_SPACE);
        var warp = OrbitalRegion.get(server).timeWarp();
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
            warp.setGear(space, Math.clamp(warp.gear + change, 0, RATES.length - 1));
        }
        int rate = RATES[warp.gear];
        player.displayClientMessage(Component.translatable(MESSAGE + "rate", rate), true);
        return rate;
    }

    int nextStep(ServerLevel space, OrbitalSavedData data) {
        if (gear == 0) {
            return 1;
        }
        var server = space.getServer();
        var owner = space.players().stream().filter(ServerPlayer::isAlive).filter(OrbitalTimeWarp::canUse)
                .findFirst().orElse(null);
        var reference = owner == null ? null : OrbitalRegion.referenceFor(owner);
        if (reference == null) {
            reset(space);
            return 1;
        }
        if (!SableShipFlight.isCoasting(space)) {
            stop(space, owner, "burn");
            return 1;
        }

        int requested = RATES[gear];
        double from = data.orbitSeconds();
        double to = (data.orbitTicks() + requested) / (double) OrbitalSavedData.TICKS_PER_SECOND;
        var limit = firstLimit(space, data, reference, from, to);
        if (limit.seconds() > to) {
            return requested;
        }
        stop(space, owner, limit.reason());
        return stepBefore(data.orbitTicks(), requested, limit.seconds());
    }

    void reset(ServerLevel space) {
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

    private static Limit firstLimit(ServerLevel space, OrbitalSavedData data, Orbit reference, double from, double to) {
        var limit = new Limit(data.nextManeuverSeconds(), "burn");
        if (limit.seconds() <= from) {
            return limit;
        }
        var radii = new HashMap<ResourceLocation, Double>();
        for (var body : SYSTEM.bodies()) {
            radii.put(body.id(), ShipOrbitHandoff.timeWarpRadius(space.getServer(), body));
        }
        var members = OrbitalRegion.get(space.getServer()).warpMembers(space);
        var referenceLegs = SYSTEM.conics(reference, from, Math.min(to, limit.seconds()));
        for (var member : members) {
            double time = firstDescent(referenceLegs, radii, member.position(), from, Math.min(to, limit.seconds()));
            limit = limit.earlier(time, "descent");
        }

        var origin = SYSTEM.forecast(referenceLegs);
        double originAcceleration = SYSTEM.maxAcceleration(referenceLegs);
        double entry = ShipServerConfig.orbitEntryDistance();
        double speed = ShipServerConfig.orbitEntrySpeed();
        for (var ship : data.ships()) {
            if (limit.seconds() <= from) {
                break;
            }
            var legs = SYSTEM.conics(ship.orbit(), from, Math.min(to, limit.seconds()));
            double descent = firstDescent(legs, radii, Vec3.ZERO, from, Math.min(to, limit.seconds()));
            limit = limit.earlier(descent, "descent");
            var target = SYSTEM.forecast(legs);
            double acceleration = originAcceleration + SYSTEM.maxAcceleration(legs);
            for (var member : members) {
                // The region's local positions are fixed during warp. Retain the member's own velocity for entry.
                var contact = new Proximity(t -> target.apply(t).subtract(origin.apply(t)).subtract(member),
                        entry, speed, acceleration);
                limit = limit.earlier(contact.firstInside(from, Math.min(to, limit.seconds())), "encounter");
            }
        }
        return limit;
    }

    static double firstDescent(List<Orbit> legs, Map<ResourceLocation, Double> radii, Vec3 offset, double from, double to) {
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
            double mu = SYSTEM.body(leg.center()).gravitationalParameter();
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

package io.github.jxt159753.blockspaceprogram.client;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitElements;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalShipView;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSystem;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** The server's star system, last authoritative orbit time and current ship conics. */
@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class ClientOrbitalState {
    private static final Map<UUID, OrbitalShipView> SHIPS = new HashMap<>();
    private static OrbitalSystem system = OrbitalSystem.legacy();
    private static long orbitTicks;
    private static boolean hasClock;
    private static int elapsedTicks;
    private static int roundTripMillis;

    private ClientOrbitalState() {}

    public static OrbitalSystem system() {
        return system;
    }

    public static void receiveSystem(OrbitElements moonOrbit) {
        system = OrbitalSystem.withMoonOrbit(moonOrbit);
    }

    public static double orbitSeconds() {
        return orbitTicks / (double) OrbitalSavedData.TICKS_PER_SECOND;
    }

    public static boolean hasClock() {
        return hasClock;
    }

    public static double orbitSeconds(float partialTick) {
        return hasClock ? interpolatedSeconds(orbitTicks, elapsedTicks, partialTick, roundTripMillis) : orbitSeconds();
    }

    static double interpolatedSeconds(long ticks, int elapsed, float partialTick, int pingMillis) {
        int ticksPerSecond = OrbitalSavedData.TICKS_PER_SECOND;
        double sinceSample = Math.clamp(elapsed + (double) partialTick, 0, ticksPerSecond);
        double transit = Math.clamp(pingMillis / 2000.0 * ticksPerSecond, 0, ticksPerSecond);
        return (ticks + sinceSample + transit) / ticksPerSecond;
    }

    public static Map<UUID, OrbitalShipView> ships() {
        return Collections.unmodifiableMap(SHIPS);
    }

    public static void receiveClock(long ticks) {
        orbitTicks = ticks;
        hasClock = true;
        elapsedTicks = 0;
        var minecraft = Minecraft.getInstance();
        var player = minecraft == null ? null : minecraft.player;
        var info = player == null ? null : player.connection.getPlayerInfo(player.getUUID());
        roundTripMillis = info == null ? 0 : info.getLatency();
    }

    @SubscribeEvent
    public static void tickClock(ClientTickEvent.Pre event) {
        // The orbital clock is shared across dimensions, including during a client-level replacement.
        if (hasClock && elapsedTicks < OrbitalSavedData.TICKS_PER_SECOND && !Minecraft.getInstance().isPaused()) {
            elapsedTicks++;
        }
    }

    public static void receiveShip(UUID id, Optional<OrbitalShipView> ship) {
        if (ship.isPresent()) {
            SHIPS.put(id, ship.get());
        } else {
            SHIPS.remove(id);
        }
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        SHIPS.clear();
        system = OrbitalSystem.legacy();
        orbitTicks = 0;
        hasClock = false;
        elapsedTicks = 0;
        roundTripMillis = 0;
    }
}

package io.github.jxt159753.blockspaceprogram.client;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalShipView;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Last authoritative orbit time and current ship conics, read on the client thread. */
@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class ClientOrbitalState {
    private static final Map<UUID, OrbitalShipView> SHIPS = new HashMap<>();
    private static long orbitTicks;
    private static boolean hasClock;
    private static ClientLevel clockLevel;
    private static long clockGameTime;

    private ClientOrbitalState() {}

    public static double orbitSeconds() {
        return orbitTicks / (double) OrbitalSavedData.TICKS_PER_SECOND;
    }

    public static boolean hasClock() {
        return hasClock;
    }

    public static double orbitSeconds(float partialTick) {
        if (clockLevel == null || clockLevel != Minecraft.getInstance().level) {
            return orbitSeconds();
        }
        double elapsed = clockLevel.getGameTime() - clockGameTime + (double) partialTick;
        // Interpolate within the server's one-second clock update interval.
        double ticks = Mth.clamp(elapsed, 0, OrbitalSavedData.TICKS_PER_SECOND);
        return orbitSeconds() + ticks / OrbitalSavedData.TICKS_PER_SECOND;
    }

    public static Map<UUID, OrbitalShipView> ships() {
        return Collections.unmodifiableMap(SHIPS);
    }

    public static void receiveClock(long ticks) {
        orbitTicks = ticks;
        hasClock = true;
        var minecraft = Minecraft.getInstance();
        clockLevel = minecraft == null ? null : minecraft.level;
        clockGameTime = clockLevel == null ? 0 : clockLevel.getGameTime();
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
        orbitTicks = 0;
        hasClock = false;
        clockLevel = null;
    }
}

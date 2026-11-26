package io.github.jxt159753.blockspaceprogram.client;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.orbit.Orbit;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/** Last authoritative orbit time and current ship conics, read on the client thread. */
@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class ClientOrbitalState {
    private static final Map<UUID, Orbit> SHIPS = new HashMap<>();
    private static long orbitTicks;

    private ClientOrbitalState() {}

    public static double orbitSeconds() {
        return orbitTicks / (double) OrbitalSavedData.TICKS_PER_SECOND;
    }

    public static Map<UUID, Orbit> ships() {
        return Collections.unmodifiableMap(SHIPS);
    }

    public static void receiveClock(long ticks) {
        orbitTicks = ticks;
    }

    public static void receiveShip(UUID id, Optional<Orbit> orbit) {
        if (orbit.isPresent()) {
            SHIPS.put(id, orbit.get());
        } else {
            SHIPS.remove(id);
        }
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        SHIPS.clear();
        orbitTicks = 0;
    }
}

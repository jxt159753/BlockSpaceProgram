package io.github.jxt159753.blockspaceprogram.orbit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.jxt159753.blockspaceprogram.client.ClientOrbitalState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.junit.Test;

public class OrbitalNetworkingTest {
    @Test
    public void packetRoundTripSynchronizesClockUpsertsAndRemovals() {
        var clock = roundTrip(OrbitalNetworking.Clock.CODEC, new OrbitalNetworking.Clock(12_345));
        ClientOrbitalState.receiveClock(clock.orbitTicks());
        assertEquals(617.25, ClientOrbitalState.orbitSeconds(), 0);

        UUID id = UUID.randomUUID();
        Orbit original = new Orbit(OrbitalSystem.MOTHER_PLANET,
                OrbitElements.fromSemiMajorAxis(70_000, 0, 0, 0, 0, 0, 0));
        Orbit patched = new Orbit(OrbitalSystem.MOON,
                OrbitElements.fromSemiMajorAxis(-55_000, 1.4, 0.3, 0.4, 0.2, 0.2, 617.1));
        receive(id, Optional.of(original));
        assertEquals(original, ClientOrbitalState.ships().get(id));
        receive(id, Optional.of(patched));
        assertEquals(patched, ClientOrbitalState.ships().get(id));
        receive(id, Optional.empty());
        assertFalse(ClientOrbitalState.ships().containsKey(id));
        receive(id, Optional.of(original));
        ClientOrbitalState.onDisconnect(new ClientPlayerNetworkEvent.LoggingOut(null, null, null));
        assertTrue(ClientOrbitalState.ships().isEmpty());
        assertEquals(0, ClientOrbitalState.orbitSeconds(), 0);
    }

    private static void receive(UUID id, Optional<Orbit> orbit) {
        var payload = roundTrip(OrbitalNetworking.ShipUpdate.CODEC, new OrbitalNetworking.ShipUpdate(id, orbit));
        ClientOrbitalState.receiveShip(payload.id(), payload.orbit());
    }

    private static <T> T roundTrip(StreamCodec<ByteBuf, T> codec, T payload) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codec.encode(buffer, payload);
            T decoded = codec.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return decoded;
        } finally {
            buffer.release();
        }
    }
}

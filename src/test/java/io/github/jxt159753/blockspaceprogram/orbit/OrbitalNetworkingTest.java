package io.github.jxt159753.blockspaceprogram.orbit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion;
import io.github.jxt159753.blockspaceprogram.client.ClientOrbitalState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;
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
        var before = new OrbitalShipView(original, Optional.of(new ManeuverNode(720, new Vec3(80, 0, 0))),
                Optional.of(new VacuumPropulsion(1500, 200, List.of(new VacuumPropulsion.Group(200, 1000, 2)))));
        var after = new OrbitalShipView(patched, Optional.empty(), Optional.empty());
        receive(id, Optional.of(before));
        assertEquals(before, ClientOrbitalState.ships().get(id));
        receive(id, Optional.of(after));
        assertEquals(after, ClientOrbitalState.ships().get(id));
        receive(id, Optional.empty());
        assertFalse(ClientOrbitalState.ships().containsKey(id));
        receive(id, Optional.of(before));
        ClientOrbitalState.onDisconnect(new ClientPlayerNetworkEvent.LoggingOut(null, null, null));
        assertTrue(ClientOrbitalState.ships().isEmpty());
        assertEquals(0, ClientOrbitalState.orbitSeconds(), 0);
    }

    private static void receive(UUID id, Optional<OrbitalShipView> ship) {
        var payload = roundTrip(OrbitalNetworking.ShipUpdate.CODEC, new OrbitalNetworking.ShipUpdate(id, ship));
        ClientOrbitalState.receiveShip(payload.id(), payload.ship());
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

package io.github.jxt159753.blockspaceprogram.orbit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign;
import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion;
import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion.Drive;
import io.github.jxt159753.blockspaceprogram.assembly.VacuumPropulsion.Group;
import io.github.jxt159753.blockspaceprogram.client.ClientOrbitalState;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.junit.Test;

public class OrbitalNetworkingTest {
    @Test
    public void starSystemSnapshotUsesSavedElementsAndClearsOnDisconnect() {
        var orbit = StarSystem.generate(StarSystem.Spacing.NEAR, StarSystem.Shape.NATURAL, 53).moonOrbit();
        var snapshot = roundTrip(OrbitalNetworking.StarSystemSnapshot.CODEC,
                new OrbitalNetworking.StarSystemSnapshot(orbit));
        assertEquals(orbit, snapshot.moonOrbit());
        ClientOrbitalState.receiveSystem(snapshot.moonOrbit());
        assertEquals(orbit, ClientOrbitalState.system().body(OrbitalSystem.MOON).orbit().elements());
        ClientOrbitalState.onDisconnect(new ClientPlayerNetworkEvent.LoggingOut(null, null, null));
        assertEquals(OrbitalSystem.LEGACY_MOON_ORBIT, ClientOrbitalState.system().body(OrbitalSystem.MOON).orbit().elements());
    }

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
                Optional.of(new VacuumPropulsion(1500, 200, 600_000, List.of(
                        new Group(0, 100, new Drive(200, 1000, -150, 2), new Drive(0, 0, 0, 0), 0),
                        new Group(2, 100, new Drive(0, 0, 0, 0), new Drive(-200, -1000, 150, 2), 4000)))));
        var after = new OrbitalShipView(patched, Optional.empty(), Optional.empty());
        receive(id, Optional.of(before));
        assertEquals(before, ClientOrbitalState.ships().get(id));
        assertTrue(ClientOrbitalState.ships().get(id).propulsion().orElseThrow().thrustCancels());
        receive(id, Optional.of(after));
        assertEquals(after, ClientOrbitalState.ships().get(id));
        receive(id, Optional.empty());
        assertFalse(ClientOrbitalState.ships().containsKey(id));
        receive(id, Optional.of(before));
        ClientOrbitalState.onDisconnect(new ClientPlayerNetworkEvent.LoggingOut(null, null, null));
        assertTrue(ClientOrbitalState.ships().isEmpty());
        assertEquals(0, ClientOrbitalState.orbitSeconds(), 0);
    }

    @Test
    public void encodesMaximumMergedPropellantGroups() {
        var orbit = new Orbit(OrbitalSystem.MOTHER_PLANET,
                OrbitElements.fromSemiMajorAxis(70_000, 0, 0, 0, 0, 0, 0));
        var drive = new Drive(0, 10, 0, 1);
        var groups = IntStream.range(108, 108 + ShipDesign.MAX_MERGED_PARTS)
                .mapToObj(index -> new Group(index, 1, drive, drive, 10)).toList();
        var view = new OrbitalShipView(orbit, Optional.empty(),
                Optional.of(new VacuumPropulsion(500, 288, 600_000, groups)));
        assertEquals(view, roundTrip(OrbitalShipView.STREAM_CODEC, view));
    }

    @Test
    public void decodesLegacyScalarPropulsion() {
        var id = UUID.randomUUID();
        var orbit = new Orbit(OrbitalSystem.MOTHER_PLANET,
                OrbitElements.fromSemiMajorAxis(70_000, 0, 0, 0, 0, 0, 0));
        ByteBuf buffer = Unpooled.buffer();
        try {
            UUIDUtil.STREAM_CODEC.encode(buffer, id);
            buffer.writeBoolean(true);
            ByteBufCodecs.fromCodec(Orbit.CODEC).encode(buffer, orbit);
            buffer.writeBoolean(false);
            buffer.writeBoolean(true);
            buffer.writeDouble(1_500).writeDouble(200);
            ByteBufCodecs.VAR_INT.encode(buffer, 1);
            buffer.writeDouble(200).writeDouble(1_000).writeDouble(2);
            var payload = OrbitalNetworking.ShipUpdate.CODEC.decode(buffer);
            assertEquals(id, payload.id());
            var ship = payload.ship().orElseThrow();
            assertEquals(orbit, ship.orbit());
            assertEquals(new VacuumPropulsion(1_500, 200, 0, List.of(
                    new Group(0, 200, new Drive(1_000, 0, 0, 2), new Drive(0, 0, 0, 0), 0))),
                    ship.propulsion().orElseThrow());
            assertEquals(0, buffer.readableBytes());
        } finally {
            buffer.release();
        }
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

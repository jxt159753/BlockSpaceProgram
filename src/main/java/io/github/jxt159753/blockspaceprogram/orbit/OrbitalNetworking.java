package io.github.jxt159753.blockspaceprogram.orbit;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.ShipOrbitHandoff;
import io.github.jxt159753.blockspaceprogram.client.ClientOrbitalState;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class OrbitalNetworking {
    private OrbitalNetworking() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(OrbitalNetworking::registerPayloads);
        NeoForge.EVENT_BUS.addListener(OrbitalNetworking::onLogin);
        NeoForge.EVENT_BUS.addListener(OrbitalNetworking::afterServerTick);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(Clock.TYPE, Clock.CODEC,
                (payload, context) -> ClientOrbitalState.receiveClock(payload.orbitTicks()));
        registrar.playToClient(ShipUpdate.TYPE, ShipUpdate.CODEC,
                (payload, context) -> ClientOrbitalState.receiveShip(payload.id(), payload.orbit()));
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            OrbitalSavedData data = OrbitalSavedData.get(player.serverLevel().getServer());
            PacketDistributor.sendToPlayer(player, new Clock(data.orbitTicks()));
            for (OrbitingShip ship : data.ships()) {
                PacketDistributor.sendToPlayer(player, new ShipUpdate(ship.id(), Optional.of(ship.orbit())));
            }
        }
    }

    private static void afterServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        OrbitalSavedData data = OrbitalSavedData.get(server);
        data.advanceTick();
        if (ModList.get().isLoaded("sable")) {
            ShipOrbitHandoff.tick(server, data);
        }
        var changes = data.takeChanges();
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        if (!changes.isEmpty() || data.orbitTicks() % OrbitalSavedData.TICKS_PER_SECOND == 0) {
            PacketDistributor.sendToAllPlayers(new Clock(data.orbitTicks()));
        }
        for (UUID id : changes) {
            var orbit = Optional.ofNullable(data.ship(id)).map(OrbitingShip::orbit);
            PacketDistributor.sendToAllPlayers(new ShipUpdate(id, orbit));
        }
    }

    record Clock(long orbitTicks) implements CustomPacketPayload {
        static final Type<Clock> TYPE = payloadType("orbital_clock");
        static final StreamCodec<ByteBuf, Clock> CODEC = ByteBufCodecs.VAR_LONG.map(Clock::new, Clock::orbitTicks);

        @Override
        public Type<Clock> type() {
            return TYPE;
        }
    }

    record ShipUpdate(UUID id, Optional<Orbit> orbit) implements CustomPacketPayload {
        static final Type<ShipUpdate> TYPE = payloadType("orbital_ship");
        static final StreamCodec<ByteBuf, ShipUpdate> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ShipUpdate::id,
                ByteBufCodecs.optional(ByteBufCodecs.fromCodec(Orbit.CODEC)), ShipUpdate::orbit,
                ShipUpdate::new);

        @Override
        public Type<ShipUpdate> type() {
            return TYPE;
        }
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, path));
    }
}

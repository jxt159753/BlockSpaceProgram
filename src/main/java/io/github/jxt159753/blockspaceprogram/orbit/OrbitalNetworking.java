package io.github.jxt159753.blockspaceprogram.orbit;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.ShipOrbitHandoff;
import io.github.jxt159753.blockspaceprogram.client.ClientOrbitalState;
import io.github.jxt159753.blockspaceprogram.client.OrbitalMapClient;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData.ManeuverResult;
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
        var registrar = event.registrar("2");
        registrar.playToClient(Clock.TYPE, Clock.CODEC,
                (payload, context) -> ClientOrbitalState.receiveClock(payload.orbitTicks()));
        registrar.playToClient(ShipUpdate.TYPE, ShipUpdate.CODEC,
                (payload, context) -> ClientOrbitalState.receiveShip(payload.id(), payload.ship()));
        registrar.playToClient(ManeuverFeedback.TYPE, ManeuverFeedback.CODEC,
                (payload, context) -> OrbitalMapClient.receiveResult(payload.id(), payload.result()));
        registrar.playToServer(ManeuverCommand.TYPE, ManeuverCommand.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                handleCommand(player, payload);
            }
        });
    }

    public static void sendManeuver(UUID ship, Optional<ManeuverNode> node) {
        PacketDistributor.sendToServer(new ManeuverCommand(ship, node));
    }

    private static void handleCommand(ServerPlayer player, ManeuverCommand command) {
        var server = player.serverLevel().getServer();
        // Ownerless ships have no per-player access lists. Only the host or an operator may burn.
        var result = player.hasPermissions(2) || server.isSingleplayerOwner(player.getGameProfile())
                ? OrbitalSavedData.get(server).commandManeuver(command.id(), command.node(), server.registryAccess())
                : ManeuverResult.FORBIDDEN;
        PacketDistributor.sendToPlayer(player, new ManeuverFeedback(command.id(), result));
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            OrbitalSavedData data = OrbitalSavedData.get(player.serverLevel().getServer());
            PacketDistributor.sendToPlayer(player, new Clock(data.orbitTicks()));
            for (OrbitingShip ship : data.ships()) {
                var view = OrbitalShipView.of(ship, player.registryAccess());
                PacketDistributor.sendToPlayer(player, new ShipUpdate(ship.id(), Optional.of(view)));
            }
        }
    }

    private static void afterServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        OrbitalSavedData data = OrbitalSavedData.get(server);
        data.advanceTick(server.registryAccess());
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
            var view = Optional.ofNullable(data.ship(id)).map(ship -> OrbitalShipView.of(ship, server.registryAccess()));
            PacketDistributor.sendToAllPlayers(new ShipUpdate(id, view));
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

    record ShipUpdate(UUID id, Optional<OrbitalShipView> ship) implements CustomPacketPayload {
        static final Type<ShipUpdate> TYPE = payloadType("orbital_ship");
        static final StreamCodec<ByteBuf, ShipUpdate> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ShipUpdate::id,
                ByteBufCodecs.optional(OrbitalShipView.STREAM_CODEC), ShipUpdate::ship,
                ShipUpdate::new);

        @Override
        public Type<ShipUpdate> type() {
            return TYPE;
        }
    }

    private record ManeuverCommand(UUID id, Optional<ManeuverNode> node) implements CustomPacketPayload {
        private static final Type<ManeuverCommand> TYPE = payloadType("orbital_maneuver");
        private static final StreamCodec<ByteBuf, ManeuverCommand> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ManeuverCommand::id,
                ByteBufCodecs.optional(ManeuverNode.STREAM_CODEC), ManeuverCommand::node, ManeuverCommand::new);

        @Override
        public Type<ManeuverCommand> type() {
            return TYPE;
        }
    }

    private record ManeuverFeedback(UUID id, ManeuverResult result) implements CustomPacketPayload {
        private static final Type<ManeuverFeedback> TYPE = payloadType("orbital_maneuver_feedback");
        private static final StreamCodec<ByteBuf, ManeuverResult> RESULT_CODEC =
                ByteBufCodecs.VAR_INT.map(index -> ManeuverResult.values()[index], ManeuverResult::ordinal);
        private static final StreamCodec<ByteBuf, ManeuverFeedback> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ManeuverFeedback::id, RESULT_CODEC, ManeuverFeedback::result, ManeuverFeedback::new);

        @Override
        public Type<ManeuverFeedback> type() {
            return TYPE;
        }
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, path));
    }
}

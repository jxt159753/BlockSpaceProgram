package io.github.jxt159753.blockspaceprogram.orbit;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.client.ClientOrbitalState;
import io.github.jxt159753.blockspaceprogram.client.OrbitalMapClient;
import io.github.jxt159753.blockspaceprogram.client.OrbitalRegionClient;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData.ManeuverResult;
import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public final class OrbitalNetworking {
    private OrbitalNetworking() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(OrbitalNetworking::registerPayloads);
        NeoForge.EVENT_BUS.addListener(OrbitalNetworking::onLogin);
        NeoForge.EVENT_BUS.addListener(OrbitalNetworking::afterServerTick);
        if (ModList.get().isLoaded("sable")) {
            NeoForge.EVENT_BUS.addListener(ShipOrbitCommands::register);
            NeoForge.EVENT_BUS.addListener(OrbitalTimeWarp::register);
            NeoForge.EVENT_BUS.addListener(OrbitalNetworking::onLogout);
            NeoForge.EVENT_BUS.addListener(OrbitalNetworking::onDimensionChange);
            NeoForge.EVENT_BUS.addListener(OrbitalNetworking::onStopping);
        }
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToClient(Clock.TYPE, Clock.CODEC,
                (payload, context) -> ClientOrbitalState.receiveClock(payload.orbitTicks()));
        registrar.playToClient(ShipUpdate.TYPE, ShipUpdate.CODEC,
                (payload, context) -> ClientOrbitalState.receiveShip(payload.id(), payload.ship()));
        registrar.playToClient(ManeuverFeedback.TYPE, ManeuverFeedback.CODEC,
                (payload, context) -> OrbitalMapClient.receiveResult(payload.id(), payload.result()));
        registrar.playToClient(RegionContacts.TYPE, RegionContacts.CODEC,
                (payload, context) -> OrbitalRegionClient.receiveContacts(payload));
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
            if (ModList.get().isLoaded("sable")) {
                OrbitalRegion.get(player.serverLevel().getServer()).onLogin(player, data);
            }
        }
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && OrbitalRegion.inSpace(player)) {
            var server = player.serverLevel().getServer();
            OrbitalRegion.get(server).onLogout(player, OrbitalSavedData.get(server));
        }
    }

    private static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            OrbitalRegion.configurePlayer(player, event.getTo().equals(BlockSpaceProgram.ORBITAL_SPACE));
        }
    }

    private static void onStopping(ServerStoppingEvent event) {
        var server = event.getServer();
        var space = server.getLevel(BlockSpaceProgram.ORBITAL_SPACE);
        if (space != null) {
            OrbitalRegion.get(server).onStopping(space, OrbitalSavedData.get(server));
        }
    }

    private static void afterServerTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        OrbitalSavedData data = OrbitalSavedData.get(server);
        boolean sable = ModList.get().isLoaded("sable");
        var space = sable ? server.getLevel(BlockSpaceProgram.ORBITAL_SPACE) : null;
        double previousSeconds = data.orbitSeconds();
        int step = space == null ? 1 : OrbitalRegion.get(server).timeWarp().nextStep(space, data);
        boolean warped = step > 1;
        data.advanceTicks(server.registryAccess(), step);
        if (sable) {
            ShipOrbitHandoff.tick(server, data, previousSeconds, warped);
        }
        var changes = data.takeChanges();
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        if (warped || !changes.isEmpty() || data.orbitTicks() % OrbitalSavedData.TICKS_PER_SECOND == 0) {
            PacketDistributor.sendToAllPlayers(new Clock(data.orbitTicks()));
        }
        for (UUID id : changes) {
            var view = Optional.ofNullable(data.ship(id)).map(ship -> OrbitalShipView.of(ship, server.registryAccess()));
            PacketDistributor.sendToAllPlayers(new ShipUpdate(id, view));
        }
        if (space != null && !space.players().isEmpty()
                && (warped || data.orbitTicks() % OrbitalRegion.CONTACT_INTERVAL == 0)) {
            var contacts = OrbitalRegion.get(server).contacts(data.orbitSeconds());
            var payload = new RegionContacts(data.orbitTicks(), contacts);
            space.players().forEach(player -> PacketDistributor.sendToPlayer(player, payload));
        }
    }

    public record Contact(UUID id, OrbitalState state) {
        private static final StreamCodec<ByteBuf, Vec3> VECTOR_CODEC = StreamCodec.composite(
                ByteBufCodecs.DOUBLE, Vec3::x,
                ByteBufCodecs.DOUBLE, Vec3::y,
                ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);
        private static final StreamCodec<ByteBuf, OrbitalState> STATE_CODEC = StreamCodec.composite(
                VECTOR_CODEC, OrbitalState::position, VECTOR_CODEC, OrbitalState::velocity, OrbitalState::new);
        private static final StreamCodec<ByteBuf, Contact> CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Contact::id, STATE_CODEC, Contact::state, Contact::new);
    }

    public record RegionContacts(long orbitTicks, List<Contact> ships) implements CustomPacketPayload {
        private static final Type<RegionContacts> TYPE = payloadType("orbital_region_contacts");
        private static final StreamCodec<ByteBuf, RegionContacts> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, RegionContacts::orbitTicks,
                Contact.CODEC.apply(ByteBufCodecs.list()), RegionContacts::ships, RegionContacts::new);

        @Override
        public Type<RegionContacts> type() {
            return TYPE;
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

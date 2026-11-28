package io.github.jxt159753.blockspaceprogram.sky;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.PlanetFlightProperties;
import io.github.jxt159753.blockspaceprogram.assembly.PlanetFlightProperties.Appearance;
import io.github.jxt159753.blockspaceprogram.client.PlanetSkyClient;
import io.github.jxt159753.blockspaceprogram.orbit.Orbit;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalRegion;
import io.github.jxt159753.blockspaceprogram.sky.surfacemap.SurfaceMapNetworking;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class SkyNetworking {
    private static final Map<ServerPlayer, Orbit> VIEWS = new WeakHashMap<>();

    private SkyNetworking() {}

    public static void register(IEventBus modBus) {
        modBus.addListener(SkyNetworking::registerPayloads);
        NeoForge.EVENT_BUS.addListener(SkyNetworking::syncAtmospheres);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, SkyNetworking::syncOrbitalViews);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(Atmospheres.TYPE, Atmospheres.CODEC,
                (payload, context) -> PlanetSkyClient.receiveAtmospheres(payload.planets()));
        registrar.playToClient(OrbitalView.TYPE, OrbitalView.CODEC,
                (payload, context) -> PlanetSkyClient.receiveOrbitalView(payload));
        registrar.optional().playToClient(Appearances.TYPE, Appearances.CODEC,
                (payload, context) -> PlanetSkyClient.receiveAppearances(payload.planets()));
        SurfaceMapNetworking.register(event);
    }

    private static void syncAtmospheres(OnDatapackSyncEvent event) {
        var payload = new Atmospheres(PlanetFlightProperties.all());
        var appearances = new Appearances(PlanetFlightProperties.appearances());
        event.getRelevantPlayers().forEach(player -> {
            PacketDistributor.sendToPlayer(player, payload);
            if (NetworkRegistry.hasChannel(player.connection, Appearances.TYPE.id())) {
                PacketDistributor.sendToPlayer(player, appearances);
            }
        });
    }

    private static void syncOrbitalViews(ServerTickEvent.Post event) {
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            Orbit reference = OrbitalRegion.referenceFor(player);
            if (reference == null) {
                if (VIEWS.remove(player) != null) {
                    clearOrbitalView(player);
                }
            } else if (VIEWS.put(player, reference) != reference) {
                sendOrbitalView(player, OrbitalRegion.ORIGIN, reference);
            }
        }
    }

    /** Sends the local physics region's origin and reference conic after entry or rebasing. */
    public static void sendOrbitalView(ServerPlayer player, Vec3 regionOrigin, Orbit reference) {
        PacketDistributor.sendToPlayer(player, new OrbitalView(player.level().dimension().location(),
                regionOrigin, Optional.of(reference)));
    }

    public static void clearOrbitalView(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new OrbitalView(player.level().dimension().location(),
                Vec3.ZERO, Optional.empty()));
    }

    private record Atmospheres(Map<ResourceLocation, PlanetFlightProperties> planets) implements CustomPacketPayload {
        private static final Type<Atmospheres> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "sky_atmospheres"));
        private static final StreamCodec<FriendlyByteBuf, Atmospheres> CODEC = StreamCodec.composite(
                ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, PlanetFlightProperties.STREAM_CODEC),
                Atmospheres::planets, Atmospheres::new);

        @Override
        public Type<Atmospheres> type() {
            return TYPE;
        }
    }

    private record Appearances(Map<ResourceLocation, Appearance> planets) implements CustomPacketPayload {
        private static final Type<Appearances> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "sky_appearance"));
        private static final StreamCodec<FriendlyByteBuf, Appearances> CODEC = StreamCodec.composite(
                ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.fromCodec(Appearance.CODEC)),
                Appearances::planets, Appearances::new);

        @Override
        public Type<Appearances> type() {
            return TYPE;
        }
    }

    public record OrbitalView(ResourceLocation dimension, Vec3 regionOrigin, Optional<Orbit> reference)
            implements CustomPacketPayload {
        private static final Type<OrbitalView> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "sky_orbital_view"));
        private static final StreamCodec<FriendlyByteBuf, Vec3> POSITION_CODEC = StreamCodec.of(
                FriendlyByteBuf::writeVec3, FriendlyByteBuf::readVec3);
        private static final StreamCodec<FriendlyByteBuf, OrbitalView> CODEC = StreamCodec.composite(
                ResourceLocation.STREAM_CODEC, OrbitalView::dimension,
                POSITION_CODEC, OrbitalView::regionOrigin,
                ByteBufCodecs.optional(ByteBufCodecs.fromCodec(Orbit.CODEC)), OrbitalView::reference,
                OrbitalView::new);

        @Override
        public Type<OrbitalView> type() {
            return TYPE;
        }
    }
}

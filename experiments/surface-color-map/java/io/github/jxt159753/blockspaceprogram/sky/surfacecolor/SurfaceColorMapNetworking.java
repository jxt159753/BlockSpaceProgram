package io.github.jxt159753.blockspaceprogram.sky.surfacecolor;

import io.github.jxt159753.blockspaceprogram.client.surfacecolor.SurfaceColorMapClient;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class SurfaceColorMapNetworking {
    public static final ResourceLocation SPLIT_PROBE = id("split_probe");

    private SurfaceColorMapNetworking() {}

    static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToClient(Manifest.TYPE, Manifest.CODEC,
                (payload, context) -> SurfaceColorMapClient.receiveManifest(payload));
        registrar.playToServer(Request.TYPE, Request.CODEC,
                (payload, context) -> SurfaceColorMapServer.request((ServerPlayer) context.player(), payload.entry()));
        registrar.playToClient(MapData.TYPE, MapData.CODEC,
                (payload, context) -> SurfaceColorMapClient.receiveMap(payload));
    }

    static boolean supportsMaps(ServerPlayer player) {
        return NetworkRegistry.hasChannel(player.connection, Manifest.TYPE.id())
                && NetworkRegistry.hasChannel(player.connection, Request.TYPE.id())
                && NetworkRegistry.hasChannel(player.connection, MapData.TYPE.id());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(SurfaceColorMapExperiment.MOD_ID, path);
    }

    public record Entry(ResourceLocation dimension, int width, String hash) {
        private static final StreamCodec<FriendlyByteBuf, Entry> CODEC = StreamCodec.of(
                (buf, entry) -> {
                    buf.writeResourceLocation(entry.dimension());
                    buf.writeVarInt(entry.width());
                    buf.writeUtf(entry.hash(), 64);
                },
                buf -> new Entry(buf.readResourceLocation(), buf.readVarInt(), buf.readUtf(64)));
    }

    public record Manifest(List<Entry> entries) implements CustomPacketPayload {
        private static final Type<Manifest> TYPE = new Type<>(id("manifest"));
        private static final StreamCodec<FriendlyByteBuf, Manifest> CODEC =
                Entry.CODEC.apply(ByteBufCodecs.list(64)).map(Manifest::new, Manifest::entries);

        @Override
        public Type<Manifest> type() {
            return TYPE;
        }
    }

    public record Request(Entry entry) implements CustomPacketPayload {
        private static final Type<Request> TYPE = new Type<>(id("request"));
        private static final StreamCodec<FriendlyByteBuf, Request> CODEC = Entry.CODEC.map(Request::new, Request::entry);

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    public record MapData(Entry entry, byte[] compressed) implements CustomPacketPayload {
        private static final Type<MapData> TYPE = new Type<>(id("map"));
        private static final StreamCodec<FriendlyByteBuf, MapData> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    Entry.CODEC.encode(buf, payload.entry());
                    buf.writeByteArray(payload.compressed());
                },
                buf -> new MapData(Entry.CODEC.decode(buf), buf.readByteArray(BiomeGrid.MAX_COMPRESSED_BYTES)));

        @Override
        public Type<MapData> type() {
            return TYPE;
        }
    }
}

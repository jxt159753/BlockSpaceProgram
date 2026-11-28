package io.github.jxt159753.blockspaceprogram.sky.surfacemap;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.client.SurfaceMapClient;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class SurfaceMapNetworking {
    private SurfaceMapNetworking() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToClient(Manifest.TYPE, Manifest.CODEC, (payload, context) -> SurfaceMapClient.receiveManifest(payload));
        registrar.playToServer(Request.TYPE, Request.CODEC,
                (payload, context) -> SurfaceMapServer.request((ServerPlayer) context.player(), payload.entry()));
        registrar.playToClient(Fragment.TYPE, Fragment.CODEC, (payload, context) -> SurfaceMapClient.receiveFragment(payload));
    }

    static boolean supported(ServerPlayer player) {
        return NetworkRegistry.hasChannel(player.connection, Manifest.TYPE.id())
                && NetworkRegistry.hasChannel(player.connection, Request.TYPE.id())
                && NetworkRegistry.hasChannel(player.connection, Fragment.TYPE.id());
    }

    private static ResourceLocation id(String name) {
        return ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "surface_map_" + name);
    }

    public record Entry(ResourceLocation dimension, int width, String hash, int fragmentCount) {
        private static final StreamCodec<FriendlyByteBuf, Entry> CODEC = StreamCodec.of((buf, entry) -> {
            buf.writeResourceLocation(entry.dimension());
            buf.writeVarInt(entry.width());
            buf.writeUtf(entry.hash(), 64);
            buf.writeVarInt(entry.fragmentCount());
        }, buf -> new Entry(buf.readResourceLocation(), buf.readVarInt(), buf.readUtf(64), buf.readVarInt()));
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

    public record Fragment(Entry entry, int index, byte[] data) implements CustomPacketPayload {
        private static final Type<Fragment> TYPE = new Type<>(id("fragment"));
        private static final StreamCodec<FriendlyByteBuf, Fragment> CODEC = StreamCodec.of((buf, fragment) -> {
            Entry.CODEC.encode(buf, fragment.entry());
            buf.writeVarInt(fragment.index());
            buf.writeByteArray(fragment.data());
        }, buf -> new Fragment(Entry.CODEC.decode(buf), buf.readVarInt(),
                buf.readByteArray(SurfaceMapTransfer.FRAGMENT_BYTES)));

        @Override
        public Type<Fragment> type() {
            return TYPE;
        }
    }
}

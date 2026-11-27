package io.github.jxt159753.blockspaceprogram.orbit;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.client.BenchmarkClient;
import io.github.jxt159753.blockspaceprogram.orbit.BenchmarkReport.Camera;
import io.github.jxt159753.blockspaceprogram.orbit.BenchmarkReport.Trial;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class BenchmarkNetworking {
    private BenchmarkNetworking() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToClient(View.TYPE, View.CODEC, (payload, context) ->
                BenchmarkClient.view(payload.entityId(), payload.yaw(), payload.pitch()));
        registrar.playToClient(Start.TYPE, Start.CODEC, (payload, context) -> BenchmarkClient.start(payload.trial()));
        registrar.playToClient(Stop.TYPE, Stop.CODEC, (payload, context) ->
                BenchmarkClient.stop(payload.id(), payload.complete()));
    }

    static boolean supported(ServerPlayer player) {
        return NetworkRegistry.hasChannel(player.connection, View.TYPE.id())
                && NetworkRegistry.hasChannel(player.connection, Start.TYPE.id())
                && NetworkRegistry.hasChannel(player.connection, Stop.TYPE.id());
    }

    static void view(ServerPlayer player, int entityId, Camera camera) {
        PacketDistributor.sendToPlayer(player, new View(entityId, camera.yaw(), camera.pitch()));
    }

    static void releaseView(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new View(-1, 0, 0));
    }

    static void start(ServerPlayer player, Trial trial) {
        PacketDistributor.sendToPlayer(player, new Start(trial));
    }

    static void stop(ServerPlayer player, Trial trial, boolean complete) {
        PacketDistributor.sendToPlayer(player, new Stop(trial.id(), complete));
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, path));
    }

    private record View(int entityId, float yaw, float pitch) implements CustomPacketPayload {
        private static final Type<View> TYPE = BenchmarkNetworking.type("benchmark_view");
        private static final StreamCodec<ByteBuf, View> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, View::entityId, ByteBufCodecs.FLOAT, View::yaw,
                ByteBufCodecs.FLOAT, View::pitch, View::new);

        @Override
        public Type<View> type() {
            return TYPE;
        }
    }

    private record Start(Trial trial) implements CustomPacketPayload {
        private static final Type<Start> TYPE = BenchmarkNetworking.type("benchmark_start");
        private static final StreamCodec<FriendlyByteBuf, Start> CODEC = StreamCodec.of((buffer, payload) -> {
            var trial = payload.trial();
            buffer.writeUtf(trial.id());
            buffer.writeUtf(trial.startedUtc());
            buffer.writeUtf(trial.scene());
            buffer.writeUtf(trial.tier());
            buffer.writeVarInt(trial.round());
            buffer.writeVarInt(trial.objectCount());
            buffer.writeVarInt(trial.partsPerShip());
            buffer.writeNullable(trial.flightState(), FriendlyByteBuf::writeUtf);
            buffer.writeNullable(trial.partLayout(), FriendlyByteBuf::writeUtf);
            buffer.writeUtf(trial.dimension());
            buffer.writeVec3(trial.origin());
            buffer.writeVec3(trial.camera().position());
            buffer.writeFloat(trial.camera().yaw());
            buffer.writeFloat(trial.camera().pitch());
            buffer.writeVarInt(trial.warmupSeconds());
            buffer.writeVarInt(trial.sampleSeconds());
            buffer.writeBoolean(trial.dedicatedServer());
        }, buffer -> new Start(new Trial(buffer.readUtf(), buffer.readUtf(), buffer.readUtf(), buffer.readUtf(),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readNullable(FriendlyByteBuf::readUtf),
                buffer.readNullable(FriendlyByteBuf::readUtf), buffer.readUtf(), buffer.readVec3(),
                new Camera(buffer.readVec3(), buffer.readFloat(), buffer.readFloat()), buffer.readVarInt(),
                buffer.readVarInt(), buffer.readBoolean())));

        @Override
        public Type<Start> type() {
            return TYPE;
        }
    }

    private record Stop(String id, boolean complete) implements CustomPacketPayload {
        private static final Type<Stop> TYPE = BenchmarkNetworking.type("benchmark_stop");
        private static final StreamCodec<ByteBuf, Stop> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Stop::id, ByteBufCodecs.BOOL, Stop::complete, Stop::new);

        @Override
        public Type<Stop> type() {
            return TYPE;
        }
    }
}

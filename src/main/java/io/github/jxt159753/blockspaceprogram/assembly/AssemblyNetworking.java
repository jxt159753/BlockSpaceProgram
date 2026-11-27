package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jetbrains.annotations.Nullable;

public final class AssemblyNetworking {
    private AssemblyNetworking() {}

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(Place.TYPE, Place.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                AssemblyMenu menu = activeMenu(player, payload.container(), payload.revision());
                if (menu != null) {
                    menu.place(player, payload);
                }
            }
        });
        registrar.playToServer(Edit.TYPE, Edit.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                AssemblyMenu menu = activeMenu(player, payload.container(), payload.revision());
                if (menu != null) {
                    menu.edit(player, payload);
                }
            }
        });
        registrar.playToServer(WorldPlace.TYPE, WorldPlace.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player
                    && player.serverLevel().getEntity(payload.target()) instanceof PlacedPartEntity part) {
                WorldPartPlacement.placeAttached(player, part, payload.hand(), payload.hit(), payload.options());
            }
        });
        registrar.playToClient(State.TYPE, State.CODEC, (payload, context) -> {
            if (context.player().containerMenu instanceof AssemblyMenu menu && menu.containerId == payload.container()) {
                menu.receive(payload);
            }
        });
    }

    @Nullable
    private static AssemblyMenu activeMenu(ServerPlayer player, int container, int revision) {
        return player.containerMenu instanceof AssemblyMenu menu && menu.containerId == container
                && menu.accepts(player, revision) ? menu : null;
    }

    public enum Action { REMOVE, ADD_STAGE, REMOVE_STAGE, MOVE_ENGINE, MOVE_SEPARATOR, SAVE, LOAD, ASSEMBLE }

    public record Place(int container, int revision, int slot, ShipPlacement.Request request) implements CustomPacketPayload {
        private static final Type<Place> TYPE = payloadType("assembly_place");
        private static final StreamCodec<FriendlyByteBuf, Place> CODEC = StreamCodec.of((buffer, payload) -> {
            buffer.writeVarInt(payload.container());
            buffer.writeVarInt(payload.revision());
            buffer.writeVarInt(payload.slot());
            var request = payload.request();
            buffer.writeVarInt(request.target());
            buffer.writeVarInt(request.targetMarker());
            buffer.writeVarInt(request.partMarker());
            buffer.writeEnum(request.targetNormal());
            buffer.writeEnum(request.partNormal());
            buffer.writeVec3(request.hit());
            buffer.writeVarInt(request.turns());
            buffer.writeVarInt(request.symmetry());
        }, buffer -> new Place(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                new ShipPlacement.Request(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                        buffer.readEnum(Direction.class), buffer.readEnum(Direction.class), buffer.readVec3(),
                        buffer.readVarInt(), buffer.readVarInt())));

        @Override
        public Type<Place> type() {
            return TYPE;
        }
    }

    public record Edit(int container, int revision, Action action, int index, int stage) implements CustomPacketPayload {
        private static final Type<Edit> TYPE = payloadType("assembly_edit");
        private static final StreamCodec<FriendlyByteBuf, Edit> CODEC = StreamCodec.of((buffer, payload) -> {
            buffer.writeVarInt(payload.container());
            buffer.writeVarInt(payload.revision());
            buffer.writeEnum(payload.action());
            buffer.writeVarInt(payload.index());
            buffer.writeVarInt(payload.stage());
        }, buffer -> new Edit(buffer.readVarInt(), buffer.readVarInt(), buffer.readEnum(Action.class),
                buffer.readVarInt(), buffer.readVarInt()));

        @Override
        public Type<Edit> type() {
            return TYPE;
        }
    }

    public record WorldPlace(int target, InteractionHand hand, Vec3 hit, WorldPartPlacement.Options options)
            implements CustomPacketPayload {
        private static final Type<WorldPlace> TYPE = payloadType("world_assembly_place");
        private static final StreamCodec<FriendlyByteBuf, WorldPlace> CODEC = StreamCodec.of((buffer, payload) -> {
            buffer.writeVarInt(payload.target());
            buffer.writeEnum(payload.hand());
            buffer.writeVec3(payload.hit());
            buffer.writeBoolean(payload.options().surface());
            buffer.writeVarInt(payload.options().source());
            buffer.writeVarInt(payload.options().turns());
        }, buffer -> new WorldPlace(buffer.readVarInt(), buffer.readEnum(InteractionHand.class), buffer.readVec3(),
                new WorldPartPlacement.Options(buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt())));

        @Override
        public Type<WorldPlace> type() {
            return TYPE;
        }
    }

    public record State(int container, int revision, ShipDesign design, String message, int count,
            @Nullable Component notice) implements CustomPacketPayload {
        private static final Type<State> TYPE = payloadType("assembly_state");
        private static final StreamCodec<RegistryFriendlyByteBuf, State> CODEC = StreamCodec.of((buffer, payload) -> {
            buffer.writeVarInt(payload.container());
            buffer.writeVarInt(payload.revision());
            ShipDesign.FLIGHT_STREAM_CODEC.encode(buffer, payload.design());
            buffer.writeUtf(payload.message(), 64);
            buffer.writeVarInt(payload.count());
            if (payload.notice() != null) {
                ComponentSerialization.STREAM_CODEC.encode(buffer, payload.notice());
            }
        }, buffer -> {
            int container = buffer.readVarInt();
            int revision = buffer.readVarInt();
            var design = ShipDesign.FLIGHT_STREAM_CODEC.decode(buffer);
            String message = buffer.readUtf(64);
            int count = buffer.readVarInt();
            var notice = buffer.isReadable() ? ComponentSerialization.STREAM_CODEC.decode(buffer) : null;
            return new State(container, revision, design, message, count, notice);
        });

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, path));
    }
}

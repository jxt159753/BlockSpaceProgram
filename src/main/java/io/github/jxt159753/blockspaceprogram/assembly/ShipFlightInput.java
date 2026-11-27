package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record ShipFlightInput(int shipId, int keys) implements CustomPacketPayload {
    public static final int PITCH_UP = 1;
    public static final int PITCH_DOWN = 1 << 1;
    public static final int YAW_LEFT = 1 << 2;
    public static final int YAW_RIGHT = 1 << 3;
    public static final int ROLL_LEFT = 1 << 4;
    public static final int ROLL_RIGHT = 1 << 5;
    public static final int THROTTLE_UP = 1 << 6;
    public static final int THROTTLE_DOWN = 1 << 7;
    public static final int CUTOFF = 1 << 8;
    public static final int STAGE = 1 << 9;
    public static final int CYCLE_ATTITUDE = 1 << 10;
    public static final int CYCLE_THROTTLE = 1 << 11;
    private static final int KEY_MASK = (CYCLE_THROTTLE << 1) - 1;
    private static final Type<ShipFlightInput> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "ship_flight_input"));
    private static final StreamCodec<FriendlyByteBuf, ShipFlightInput> CODEC = StreamCodec.of((buf, input) -> {
        buf.writeVarInt(input.shipId);
        buf.writeShort(input.keys);
    }, buf -> new ShipFlightInput(buf.readVarInt(), buf.readUnsignedShort()));

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(TYPE, CODEC, (input, context) -> {
            if ((input.keys & ~KEY_MASK) == 0 && context.player() instanceof ServerPlayer player
                    && player.getVehicle() instanceof AssembledShipEntity ship && ship.getId() == input.shipId) {
                ship.acceptFlightInput(player, input.keys);
            }
        });
    }

    static int axis(int keys, int positive, int negative) {
        return Integer.bitCount(keys & positive) - Integer.bitCount(keys & negative);
    }

    @Override
    public Type<ShipFlightInput> type() {
        return TYPE;
    }
}

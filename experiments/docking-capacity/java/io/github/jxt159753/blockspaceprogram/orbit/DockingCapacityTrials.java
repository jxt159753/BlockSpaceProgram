package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.DockingCapacityDesigns;
import io.github.jxt159753.blockspaceprogram.assembly.DockingCapacityDesigns.Shape;
import io.github.jxt159753.blockspaceprogram.assembly.SableShipFlight;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign;
import io.netty.buffer.Unpooled;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID)
public final class DockingCapacityTrials {
    private static final Logger LOGGER = LogUtils.getLogger();

    private DockingCapacityTrials() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var landed = steady(BenchmarkScene.Kind.LANDED);
        var drifting = steady(BenchmarkScene.Kind.DRIFTING);
        var root = Commands.literal("bsp_capacity").requires(source -> source.hasPermission(2))
                .then(Commands.literal("steady").then(landed).then(drifting))
                .then(DockingCapacityOperations.commands())
                .then(Commands.literal("watch").then(Commands.argument("parts", IntegerArgumentType.integer(2, DockingCapacityDesigns.MAX_PARTS))
                        .then(Commands.argument("hulls", IntegerArgumentType.integer(2, DockingCapacityDesigns.MAX_HULLS))
                                .then(Commands.argument("blocks", IntegerArgumentType.integer(1_024, 8_192))
                                        .executes(context -> watch(context.getSource().getPlayerOrException(), shape(context)))))));
        event.getDispatcher().register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> steady(BenchmarkScene.Kind kind) {
        return Commands.literal(kind.id())
                .then(Commands.argument("parts", IntegerArgumentType.integer(2, DockingCapacityDesigns.MAX_PARTS))
                        .then(Commands.argument("hulls", IntegerArgumentType.integer(2, DockingCapacityDesigns.MAX_HULLS))
                                .then(Commands.argument("blocks", IntegerArgumentType.integer(1_024, 8_192))
                                        .then(Commands.argument("warmup", IntegerArgumentType.integer(0, 300))
                                                .then(Commands.argument("sample", IntegerArgumentType.integer(1, 300))
                                                        .executes(context -> {
                                                            requireSable();
                                                            return BenchmarkCommands.capacity(context.getSource(), shape(context), kind,
                                                                    arg(context, "warmup"), arg(context, "sample"));
                                                        }))))));
    }

    private static int arg(CommandContext<CommandSourceStack> context, String name) {
        return IntegerArgumentType.getInteger(context, name);
    }

    private static Shape shape(CommandContext<CommandSourceStack> context) {
        int parts = arg(context, "parts");
        int hulls = arg(context, "hulls");
        int blocks = arg(context, "blocks");
        if (hulls > 2 * parts || (blocks != 1_024 && blocks != 4_096 && blocks != 8_192)) {
            throw new IllegalArgumentException("Each part supports up to two hulls. Use 1024, 4096, or 8192 blocks.");
        }
        return new Shape(parts, hulls, blocks);
    }

    private static void requireSable() {
        if (!ModList.get().isLoaded("sable")) {
            throw new IllegalStateException("This trial requires Sable");
        }
    }

    private static int watch(ServerPlayer player, Shape shape) {
        requireSable();
        var level = player.server.overworld();
        // Keep the entity position near its command pod even when the hull extends far down.
        var design = DockingCapacityDesigns.centerOnFirstPod(DockingCapacityDesigns.pair(shape).merged());
        var bounds = design.bounds();
        var origin = new Vec3(256, -60 - bounds.minY, 0);
        var ship = DockingCapacityDesigns.spawn(level, design, origin, shape.id() + "_watch", true);
        measureData(ship);
        // The entity's distance culling uses the command pod's small bounds.
        var target = origin.add(bounds.getCenter().x, bounds.getCenter().y, 2);
        var view = target.add(12, -player.getEyeHeight(), -4);
        float yaw = (float) Math.toDegrees(Math.atan2(12, 4));
        LOGGER.info("CAPACITY ENTER name={} epoch_ms={}", ship.getName().getString(), System.currentTimeMillis());
        SableShipFlight.movePlayer(player, level, view, Vec3.ZERO, yaw, 0);
        return 1;
    }

    @SubscribeEvent
    public static void tracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof AssembledShipEntity ship && DockingCapacityDesigns.isTrialShip(ship)) {
            LOGGER.info("CAPACITY TRACK name={} uuid={} epoch_ms={}", ship.getName().getString(), ship.getUUID(),
                    System.currentTimeMillis());
        }
    }

    static void measureData(AssembledShipEntity ship) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), ship.registryAccess());
        try {
            ShipDesign.FLIGHT_STREAM_CODEC.encode(buffer, ship.design());
            var snapshot = ship.orbitSnapshot();
            Tag layout = snapshot.getCompound("Blueprint").getCompound("components").get("blockspaceprogram:flight_layout");
            int layoutNbt = size(layout);
            int entityNbt = size(snapshot);
            LOGGER.info("CAPACITY DATA name={} uuid={} nodes={} layout_wire={} layout_nbt={} entity_nbt={}",
                    ship.getName().getString(), ship.getUUID(), ship.design().nodes().size(),
                    buffer.readableBytes(), layoutNbt, entityNbt);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot measure flight layout NBT", error);
        } finally {
            buffer.release();
        }
    }

    private static int size(Tag nbt) throws IOException {
        var output = new DataOutputStream(OutputStream.nullOutputStream());
        NbtIo.writeAnyTag(nbt, output);
        return output.size();
    }
}

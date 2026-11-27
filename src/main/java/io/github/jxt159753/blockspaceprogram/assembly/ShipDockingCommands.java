package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.jetbrains.annotations.Nullable;

/** Players operate their current ship's ports. Operators may also address an uncrewed ship. */
final class ShipDockingCommands {
    private ShipDockingCommands() {}

    static void register(RegisterCommandsEvent event) {
        var root = actions(Commands.literal("bsp_dock"), context -> {
            var player = context.getSource().getPlayer();
            return player != null && player.getVehicle() instanceof AssembledShipEntity ship
                    && ship.getFirstPassenger() == player && !player.isSpectator() ? ship.activeFlight() : null;
        });
        var target = actions(Commands.argument("target", UuidArgument.uuid()), context -> {
            var entity = context.getSource().getLevel().getEntity(UuidArgument.getUuid(context, "target"));
            return entity instanceof AssembledShipEntity ship ? ship.activeFlight() : null;
        });
        root.then(Commands.literal("ship").requires(source -> source.hasPermission(2)).then(target));
        event.getDispatcher().register(root);
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T actions(T root,
            Function<CommandContext<CommandSourceStack>, SableShipFlight> select) {
        return root.executes(context -> list(context.getSource(), select.apply(context)))
                .then(Commands.literal("arm").executes(context -> arm(context.getSource(), select.apply(context))))
                .then(Commands.literal("release").then(Commands.argument("connection", IntegerArgumentType.integer(1))
                        .executes(context -> release(context.getSource(), select.apply(context),
                                IntegerArgumentType.getInteger(context, "connection") - 1))));
    }

    private static int list(CommandSourceStack source, @Nullable SableShipFlight flight) {
        if (flight == null) {
            return error(source, "no_ship");
        }
        int count = 0;
        var links = flight.layout().links();
        for (int index = 0; index < links.size(); index++) {
            var link = links.get(index);
            if (link.docking()) {
                int number = index + 1;
                source.sendSuccess(() -> Component.translatable("docking.blockspaceprogram.connection", number,
                        link.first().node() + 1, link.first().marker() + 1,
                        link.second().node() + 1, link.second().marker() + 1), false);
                count++;
            }
        }
        return count == 0 ? error(source, "no_connections") : count;
    }

    private static int arm(CommandSourceStack source, @Nullable SableShipFlight flight) {
        if (flight == null) {
            return error(source, "no_ship");
        }
        flight.armDocking();
        source.sendSuccess(() -> Component.translatable("docking.blockspaceprogram.armed"), false);
        return 1;
    }

    private static int release(CommandSourceStack source, @Nullable SableShipFlight flight, int index) {
        if (flight == null) {
            return error(source, "no_ship");
        }
        if (!flight.releaseDocking(index)) {
            return error(source, "no_connection");
        }
        source.sendSuccess(() -> Component.translatable("docking.blockspaceprogram.released", index + 1), false);
        return 1;
    }

    private static int error(CommandSourceStack source, String key) {
        source.sendFailure(Component.translatable("docking.blockspaceprogram." + key));
        return 0;
    }
}

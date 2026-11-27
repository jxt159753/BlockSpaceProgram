package io.github.jxt159753.blockspaceprogram.facility;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class FacilityCommands {
    private FacilityCommands() {}

    public static void register(RegisterCommandsEvent event) {
        var get = Commands.literal("get")
                .executes(context -> get(context.getSource(), context.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> get(context.getSource(), EntityArgument.getPlayer(context, "player"))));
        var target = Commands.argument("player", EntityArgument.player());
        for (Facility facility : Facility.values()) {
            target.then(Commands.literal(facility.id())
                    .then(Commands.argument("tier", IntegerArgumentType.integer(1))
                            .executes(context -> set(context.getSource(), EntityArgument.getPlayer(context, "player"),
                                    facility, IntegerArgumentType.getInteger(context, "tier")))));
        }
        event.getDispatcher().register(Commands.literal("bsp_facility").then(get)
                .then(Commands.literal("set").requires(source -> source.hasPermission(2)).then(target)));
    }

    private static int get(CommandSourceStack source, ServerPlayer player) {
        var levels = BlockSpaceProgram.facilities();
        var owner = levels.owner(player);
        source.sendSuccess(() -> Component.translatable("facility.blockspaceprogram.command_get", levels.ownerName(owner)), false);
        for (Facility facility : Facility.values()) {
            var limit = levels.limit(player, owner, facility);
            source.sendSuccess(() -> Component.translatable("facility.blockspaceprogram.command_level",
                    facility.displayName(), limit.tier(), facility.maximum(limit.maximum())), false);
        }
        return 1;
    }

    private static int set(CommandSourceStack source, ServerPlayer player, Facility facility, int tier) {
        var levels = BlockSpaceProgram.facilities();
        var owner = levels.owner(player);
        if (owner == null) {
            source.sendFailure(Component.translatable("facility.blockspaceprogram.command_no_owner"));
            return 0;
        }
        int tierCount = levels.tierCount(facility);
        if (tier > tierCount) {
            source.sendFailure(Component.translatable("facility.blockspaceprogram.command_invalid_tier",
                    facility.displayName(), tierCount));
            return 0;
        }
        levels.setTier(player, owner, facility, tier);
        var limit = levels.limit(player, owner, facility);
        source.sendSuccess(() -> Component.translatable("facility.blockspaceprogram.command_set",
                owner.name(), facility.displayName(), limit.tier(), facility.maximum(limit.maximum())), false);
        return 1;
    }
}

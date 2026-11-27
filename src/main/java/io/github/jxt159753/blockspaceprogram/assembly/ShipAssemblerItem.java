package io.github.jxt159753.blockspaceprogram.assembly;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.fml.ModList;

public final class ShipAssemblerItem extends Item {
    public ShipAssemblerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        boolean sable = ModList.get().isLoaded("sable");
        if (sable && SableHullInteractions.inSubLevel(context.getLevel(), context.getClickedPos())) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        Component error = sable ? HullAssembly.assemble(player, context.getClickedPos())
                : Component.translatable("assembly.blockspaceprogram.hull_requires_sable");
        if (error != null) {
            player.displayClientMessage(error, true);
            return InteractionResult.FAIL;
        }
        player.displayClientMessage(Component.translatable("assembly.blockspaceprogram.world_assembled"), true);
        return InteractionResult.CONSUME;
    }
}

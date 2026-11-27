package io.github.jxt159753.blockspaceprogram.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class AssemblyTableBlock extends Block {
    public AssemblyTableBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            if (!player.getAbilities().mayBuild || !level.mayInteract(player, pos)) {
                return InteractionResult.FAIL;
            }
            int maximumParts = (int) ShipFacilityLimits.parts(serverPlayer).maximum();
            serverPlayer.openMenu(new SimpleMenuProvider(
                    (id, inventory, owner) -> new AssemblyMenu(id, inventory, ContainerLevelAccess.create(level, pos)),
                    getName()), buffer -> buffer.writeVarInt(maximumParts));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}

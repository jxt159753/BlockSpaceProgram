package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public final class FlightTerminalBlock extends BaseEntityBlock {
    private static final MapCodec<FlightTerminalBlock> CODEC = simpleCodec(FlightTerminalBlock::new);

    public FlightTerminalBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<FlightTerminalBlock> codec() {
        return CODEC;
    }

    @Override
    public FlightTerminalBlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FlightTerminalBlockEntity(pos, state);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (!level.isClientSide && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof FlightTerminalBlockEntity terminal) {
            terminal.claim(player);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof FlightTerminalBlockEntity terminal) {
            if (!terminal.canConfigure(player)) {
                return InteractionResult.FAIL;
            }
            terminal.claim(player);
            FlightTerminalMenu.open(serverPlayer, terminal, getName());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}

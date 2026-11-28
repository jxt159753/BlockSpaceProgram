package io.github.jxt159753.blockspaceprogram.experiment.functionblockmodels;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(FunctionBlockModels.ID)
public final class FunctionBlockModels {
    static final String ID = "bsp_function_block_models";
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);

    public FunctionBlockModels(IEventBus modBus) {
        ITEMS.registerSimpleBlockItem(BLOCKS.registerBlock("json_nozzle", NozzleBlock::new));
        ITEMS.registerSimpleBlockItem(BLOCKS.registerBlock("obj_nozzle", NozzleBlock::new));
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
    }

    private static final class NozzleBlock extends DirectionalBlock {
        private static final MapCodec<NozzleBlock> CODEC = simpleCodec(NozzleBlock::new);

        NozzleBlock(Properties properties) {
            super(properties.noOcclusion());
            registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN));
        }

        @Override
        protected MapCodec<NozzleBlock> codec() {
            return CODEC;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Override
        public BlockState getStateForPlacement(BlockPlaceContext context) {
            return defaultBlockState().setValue(FACING, context.getClickedFace());
        }

        @Override
        protected BlockState rotate(BlockState state, Rotation rotation) {
            return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        }

        @Override
        protected BlockState mirror(BlockState state, Mirror mirror) {
            return state.rotate(mirror.getRotation(state.getValue(FACING)));
        }
    }
}

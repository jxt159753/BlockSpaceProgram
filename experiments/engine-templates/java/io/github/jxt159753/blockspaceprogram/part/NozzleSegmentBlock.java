package io.github.jxt159753.blockspaceprogram.part;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

final class NozzleSegmentBlock extends Block {
    private static final Map<Direction, BooleanProperty> CONNECTIONS = Map.of(
            Direction.NORTH, BlockStateProperties.NORTH, Direction.EAST, BlockStateProperties.EAST,
            Direction.SOUTH, BlockStateProperties.SOUTH, Direction.WEST, BlockStateProperties.WEST);

    NozzleSegmentBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(BlockStateProperties.NORTH, false)
                .setValue(BlockStateProperties.EAST, false).setValue(BlockStateProperties.SOUTH, false)
                .setValue(BlockStateProperties.WEST, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.NORTH, BlockStateProperties.EAST, BlockStateProperties.SOUTH, BlockStateProperties.WEST);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (var connection : CONNECTIONS.entrySet()) {
            BlockPos neighbor = context.getClickedPos().relative(connection.getKey());
            state = state.setValue(connection.getValue(), context.getLevel().getBlockState(neighbor).is(this));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        var property = CONNECTIONS.get(direction);
        return property == null ? state : state.setValue(property, neighbor.is(this));
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        BlockState rotated = state;
        for (var connection : CONNECTIONS.entrySet()) {
            rotated = rotated.setValue(CONNECTIONS.get(rotation.rotate(connection.getKey())), state.getValue(connection.getValue()));
        }
        return rotated;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState reflected = state;
        for (var connection : CONNECTIONS.entrySet()) {
            reflected = reflected.setValue(CONNECTIONS.get(mirror.mirror(connection.getKey())),
                    state.getValue(connection.getValue()));
        }
        return reflected;
    }
}

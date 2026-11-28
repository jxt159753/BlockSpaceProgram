package io.github.jxt159753.blockspaceprogram.part;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
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

import static net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;

public final class NozzleSegmentBlock extends Block {
    private static final List<BooleanProperty> CONNECTIONS = List.of(
            BlockStateProperties.NORTH, BlockStateProperties.EAST, BlockStateProperties.SOUTH, BlockStateProperties.WEST);

    // The four model sides after the x/y rotations in blockstates/nozzle_segment.json.
    private static final Map<Direction, List<Direction>> SIDES = Map.of(
            Direction.DOWN, List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST),
            Direction.UP, List.of(Direction.SOUTH, Direction.EAST, Direction.NORTH, Direction.WEST),
            Direction.NORTH, List.of(Direction.UP, Direction.EAST, Direction.DOWN, Direction.WEST),
            Direction.EAST, List.of(Direction.UP, Direction.SOUTH, Direction.DOWN, Direction.NORTH),
            Direction.SOUTH, List.of(Direction.UP, Direction.WEST, Direction.DOWN, Direction.EAST),
            Direction.WEST, List.of(Direction.UP, Direction.NORTH, Direction.DOWN, Direction.SOUTH));

    public NozzleSegmentBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.DOWN)
                .setValue(BlockStateProperties.NORTH, false).setValue(BlockStateProperties.EAST, false)
                .setValue(BlockStateProperties.SOUTH, false).setValue(BlockStateProperties.WEST, false));
    }

    static List<Direction> sides(Direction facing) {
        return SIDES.get(facing);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, BlockStateProperties.NORTH, BlockStateProperties.EAST,
                BlockStateProperties.SOUTH, BlockStateProperties.WEST);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Direction clickedFace = context.getClickedFace();
        BlockState support = context.getLevel().getBlockState(pos.relative(clickedFace.getOpposite()));
        Direction facing = context.getNearestLookingDirection().getOpposite();
        if (support.getBlock() instanceof CombustionChamberBlock && support.getValue(FACING) == clickedFace) {
            facing = clickedFace;
        } else if (support.is(this) && support.getValue(FACING).getAxis() != clickedFace.getAxis()) {
            facing = support.getValue(FACING);
        }
        BlockState state = defaultBlockState().setValue(FACING, facing);
        for (int i = 0; i < CONNECTIONS.size(); i++) {
            BlockState neighbor = context.getLevel().getBlockState(pos.relative(sides(facing).get(i)));
            state = state.setValue(CONNECTIONS.get(i), connects(facing, neighbor));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction side, BlockState neighbor,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        Direction facing = state.getValue(FACING);
        int index = sides(facing).indexOf(side);
        return index < 0 ? state : state.setValue(CONNECTIONS.get(index), connects(facing, neighbor));
    }

    private boolean connects(Direction facing, BlockState neighbor) {
        return neighbor.is(this) && neighbor.getValue(FACING) == facing;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return transform(state, rotation::rotate);
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return transform(state, mirror::mirror);
    }

    private static BlockState transform(BlockState state, UnaryOperator<Direction> directionTransform) {
        Direction facing = state.getValue(FACING);
        Direction rotatedFacing = directionTransform.apply(facing);
        BlockState rotated = state.setValue(FACING, rotatedFacing);
        for (int i = 0; i < CONNECTIONS.size(); i++) {
            Direction side = directionTransform.apply(sides(facing).get(i));
            int index = sides(rotatedFacing).indexOf(side);
            rotated = rotated.setValue(CONNECTIONS.get(index), state.getValue(CONNECTIONS.get(i)));
        }
        return rotated;
    }
}

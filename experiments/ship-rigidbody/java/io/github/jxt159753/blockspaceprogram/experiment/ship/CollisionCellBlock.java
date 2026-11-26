package io.github.jxt159753.blockspaceprogram.experiment.ship;

import net.minecraft.core.BlockPos;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class CollisionCellBlock extends Block {
    private static final EnumProperty<Span> X = EnumProperty.create("x_span", Span.class);
    private static final EnumProperty<Span> Y = EnumProperty.create("y_span", Span.class);
    private static final EnumProperty<Span> Z = EnumProperty.create("z_span", Span.class);

    CollisionCellBlock() {
        super(Properties.of().noOcclusion().noLootTable());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(X, Y, Z);
    }

    BlockState state(ShipBody.Cell cell) {
        return defaultBlockState()
                .setValue(X, Span.between(cell.min().getX(), cell.max().getX()))
                .setValue(Y, Span.between(cell.min().getY(), cell.max().getY()))
                .setValue(Z, Span.between(cell.min().getZ(), cell.max().getZ()));
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Span x = state.getValue(X);
        Span y = state.getValue(Y);
        Span z = state.getValue(Z);
        return Shapes.box(x.min * 0.25, y.min * 0.25, z.min * 0.25,
                x.max * 0.25, y.max * 0.25, z.max * 0.25);
    }

    private enum Span implements StringRepresentable {
        Q01(0, 1), Q02(0, 2), Q03(0, 3), Q04(0, 4),
        Q12(1, 2), Q13(1, 3), Q14(1, 4), Q23(2, 3), Q24(2, 4), Q34(3, 4);

        private static final Span[][] BY_BOUNDS = new Span[4][5];

        static {
            for (Span span : values()) {
                BY_BOUNDS[span.min][span.max] = span;
            }
        }

        private final int min;
        private final int max;

        Span(int min, int max) {
            this.min = min;
            this.max = max;
        }

        static Span between(int min, int max) {
            return BY_BOUNDS[min][max];
        }

        @Override
        public String getSerializedName() {
            return min + "_" + max;
        }
    }
}

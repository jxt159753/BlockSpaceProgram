package io.github.jxt159753.blockspaceprogram.part;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;

public final class BakedPartItem extends Item {
    public BakedPartItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        BakedPart part = stack.get(BlockSpaceProgram.PART_DATA);
        Player player = context.getPlayer();
        if (part == null || player == null || player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }

        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        Direction face = context.getClickedFace();
        BlockPos adjacent = clicked.relative(face);
        if (!player.mayUseItemAt(adjacent, face, stack) || !level.mayInteract(player, adjacent)) {
            return InteractionResult.FAIL;
        }

        AABB local = part.localBounds();
        double x = clicked.getX() + (1 - local.getXsize()) / 2 + face.getStepX() * (1 + local.getXsize()) / 2;
        double y = clicked.getY() + (face == Direction.UP ? 1 : face == Direction.DOWN ? -local.getYsize() : 0);
        double z = clicked.getZ() + (1 - local.getZsize()) / 2 + face.getStepZ() * (1 + local.getZsize()) / 2;
        AABB bounds = local.move(x, y, z);
        var shape = Shapes.create(bounds);
        if (shape.isEmpty() || bounds.minY < level.getMinBuildHeight() || bounds.maxY > level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(bounds) || !level.noCollision(bounds)
                || !level.isUnobstructed(null, shape)) {
            return InteractionResult.FAIL;
        }
        if (!level.isClientSide) {
            PlacedPartEntity entity = new PlacedPartEntity(BlockSpaceProgram.PLACED_PART.get(), level);
            entity.setPartStack(stack);
            entity.setPos(x, y, z);
            if (!level.addFreshEntity(entity)) {
                return InteractionResult.FAIL;
            }
            if (!player.hasInfiniteMaterials()) {
                stack.shrink(1);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BakedPart part = stack.get(BlockSpaceProgram.PART_DATA);
        if (part == null || !player.isSecondaryUseActive()) {
            return InteractionResultHolder.pass(stack);
        }
        if (!level.isClientSide) {
            float scale = part.nextScale();
            stack.set(BlockSpaceProgram.PART_DATA, part.withScale(scale));
            player.displayClientMessage(Component.translatable("item.blockspaceprogram.baked_part.scale", scale), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        BakedPart part = stack.get(BlockSpaceProgram.PART_DATA);
        if (part == null) {
            return;
        }
        tooltip.add(Component.translatable("item.blockspaceprogram.baked_part.scale", part.scale()));
        tooltip.add(Component.translatable("item.blockspaceprogram.baked_part.mass", part.properties().mass()));
        tooltip.add(Component.translatable("item.blockspaceprogram.baked_part.impact_resistance", part.properties().impactResistance()));
        part.types().stream().sorted().forEach(type -> tooltip.add(Component.translatable("part_type.blockspaceprogram." + type.getSerializedName())));
        for (PartConnector connector : part.connectors()) {
            var center = part.modelToLocal(connector.center());
            tooltip.add(Component.translatable("item.blockspaceprogram.baked_part.connector",
                    Component.translatable("connector.blockspaceprogram." + connector.kind().getSerializedName()),
                    Component.translatable("direction.blockspaceprogram." + connector.normal().getName()),
                    connector.width() * part.scale(), connector.height() * part.scale(), center.x, center.y, center.z));
        }
        part.properties().fuelCapacity().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(fuel -> {
            Component name = Component.translatableWithFallback(fuel.getKey().toLanguageKey("fuel"), fuel.getKey().toString());
            tooltip.add(Component.translatable("item.blockspaceprogram.baked_part.fuel", name, fuel.getValue()));
        });
    }
}

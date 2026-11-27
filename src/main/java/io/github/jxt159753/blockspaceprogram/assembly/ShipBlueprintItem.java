package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.List;
import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public final class ShipBlueprintItem extends Item {
    public ShipBlueprintItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        ShipDesign design = stack.get(BlockSpaceProgram.SHIP_DESIGN);
        if (design == null) {
            tooltip.add(Component.translatable("item.blockspaceprogram.ship_blueprint.blank"));
            return;
        }
        tooltip.add(Component.translatable("item.blockspaceprogram.ship_blueprint.summary", design.partCount(), design.stageCount()));
        int hullBlocks = design.nodes().stream().map(ShipDesign.Node::hull).filter(Objects::nonNull).mapToInt(HullRegion::size).sum();
        if (hullBlocks > 0) {
            tooltip.add(Component.translatable("item.blockspaceprogram.ship_blueprint.hull_blocks", hullBlocks));
        }
    }
}

package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.List;
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
        tooltip.add(design == null ? Component.translatable("item.blockspaceprogram.ship_blueprint.blank")
                : Component.translatable("item.blockspaceprogram.ship_blueprint.summary", design.nodes().size(), design.stageCount()));
    }
}

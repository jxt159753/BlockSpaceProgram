package io.github.jxt159753.blockspaceprogram.part;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public final class BakedPartItem extends Item {
    public BakedPartItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        BakedPart part = stack.get(BlockSpaceProgram.PART_DATA);
        if (part == null) {
            return;
        }
        tooltip.add(Component.translatable("item.blockspaceprogram.baked_part.mass", part.properties().mass()));
        part.properties().fuelCapacity().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(fuel -> {
            Component name = Component.translatableWithFallback(fuel.getKey().toLanguageKey("fuel"), fuel.getKey().toString());
            tooltip.add(Component.translatable("item.blockspaceprogram.baked_part.fuel", name, fuel.getValue()));
        });
    }
}

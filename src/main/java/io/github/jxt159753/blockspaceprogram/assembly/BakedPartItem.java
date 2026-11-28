package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.PartConnector;
import java.util.List;
import java.util.Map;
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

public final class BakedPartItem extends Item {
    public BakedPartItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        BakedPart part = stack.get(BlockSpaceProgram.PART_DATA);
        return part == null ? super.getName(stack) : part.name().orElseGet(() -> super.getName(stack));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        BakedPart part = stack.get(BlockSpaceProgram.PART_DATA);
        Player player = context.getPlayer();
        if (part == null || player == null || player.isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }

        return WorldPartPlacement.placeOnBlock(context, player, part);
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
        if (!part.engines().isEmpty()) {
            var properties = part.properties();
            tooltip.add(Component.translatable("item.blockspaceprogram.baked_part.thrust", properties.thrust()));
            tooltip.add(Component.translatable("item.blockspaceprogram.baked_part.specific_impulse",
                    properties.vacuumSpecificImpulse(), properties.seaLevelSpecificImpulse() > 0
                            ? properties.seaLevelSpecificImpulse() : properties.vacuumSpecificImpulse()));
            if (properties.electricPower() > 0) {
                tooltip.add(Component.translatable("item.blockspaceprogram.baked_part.electric_power", properties.electricPower()));
            }
        }
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

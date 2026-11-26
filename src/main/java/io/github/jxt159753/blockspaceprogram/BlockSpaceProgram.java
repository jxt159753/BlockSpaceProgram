package io.github.jxt159753.blockspaceprogram;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.BakedPartItem;
import io.github.jxt159753.blockspaceprogram.part.BakingTableBlock;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(BlockSpaceProgram.MOD_ID)
public final class BlockSpaceProgram {
    public static final String MOD_ID = "blockspaceprogram";

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);

    public static final DeferredBlock<BakingTableBlock> BAKING_TABLE = BLOCKS.registerBlock(
            "baking_table", BakingTableBlock::new, BlockBehaviour.Properties.of().strength(2.5F));
    public static final DeferredItem<BakedPartItem> BAKED_PART = ITEMS.registerItem(
            "baked_part", BakedPartItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BakedPart>> PART_DATA =
            COMPONENTS.registerComponentType("part_data", builder -> builder.persistent(BakedPart.CODEC));

    static {
        ITEMS.registerSimpleBlockItem(BAKING_TABLE);
    }

    public BlockSpaceProgram(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener(BlockPartProperties::register);
        modBus.addListener(this::addCreativeTabItems);
        LogUtils.getLogger().info("BlockSpaceProgram loaded");
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.FUNCTIONAL_BLOCKS)) {
            event.accept(BAKING_TABLE);
        }
    }
}

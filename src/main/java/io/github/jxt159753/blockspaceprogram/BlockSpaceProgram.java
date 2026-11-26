package io.github.jxt159753.blockspaceprogram;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.collision.CollisionCellBlock;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.BakedPartItem;
import io.github.jxt159753.blockspaceprogram.part.BakingTableBlock;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import io.github.jxt159753.blockspaceprogram.part.PlacedPartEntity;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
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
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);

    public static final DeferredBlock<BakingTableBlock> BAKING_TABLE = BLOCKS.registerBlock(
            "baking_table", BakingTableBlock::new, BlockBehaviour.Properties.of().strength(2.5F));
    public static final DeferredBlock<CollisionCellBlock> SHIP_COLLISION_CELL = BLOCKS.registerBlock(
            "ship_collision_cell", CollisionCellBlock::new,
            BlockBehaviour.Properties.of().strength(-1.0F, 3_600_000.0F).noOcclusion().noLootTable());
    public static final DeferredItem<BakedPartItem> BAKED_PART = ITEMS.registerItem(
            "baked_part", BakedPartItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BakedPart>> PART_DATA =
            COMPONENTS.registerComponentType("part_data", builder -> builder.persistent(BakedPart.CODEC));
    public static final DeferredHolder<EntityType<?>, EntityType<PlacedPartEntity>> PLACED_PART = ENTITIES.register(
            "placed_part", id -> EntityType.Builder.of(PlacedPartEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F).clientTrackingRange(10).build(id.toString()));

    static {
        ITEMS.registerSimpleBlockItem(BAKING_TABLE);
    }

    public BlockSpaceProgram(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
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

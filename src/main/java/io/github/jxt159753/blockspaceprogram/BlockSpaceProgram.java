package io.github.jxt159753.blockspaceprogram;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.AssemblyMenu;
import io.github.jxt159753.blockspaceprogram.assembly.AssemblyNetworking;
import io.github.jxt159753.blockspaceprogram.assembly.AssemblyTableBlock;
import io.github.jxt159753.blockspaceprogram.assembly.LaunchPadBlock;
import io.github.jxt159753.blockspaceprogram.assembly.SableCollisionCellBlock;
import io.github.jxt159753.blockspaceprogram.assembly.ShipBlueprintItem;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDebrisConfig;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightInput;
import io.github.jxt159753.blockspaceprogram.collision.CollisionCellBlock;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalNetworking;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.BakedPartItem;
import io.github.jxt159753.blockspaceprogram.part.BakingTableBlock;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import io.github.jxt159753.blockspaceprogram.part.PartConnector;
import io.github.jxt159753.blockspaceprogram.part.PartConnectorBlock;
import io.github.jxt159753.blockspaceprogram.part.PlacedPartEntity;
import io.github.jxt159753.blockspaceprogram.sky.SkyNetworking;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
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
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);

    public static final DeferredBlock<BakingTableBlock> BAKING_TABLE = BLOCKS.registerBlock(
            "baking_table", BakingTableBlock::new, BlockBehaviour.Properties.of().strength(2.5F));
    public static final DeferredBlock<AssemblyTableBlock> ASSEMBLY_TABLE = BLOCKS.registerBlock(
            "assembly_table", AssemblyTableBlock::new, BlockBehaviour.Properties.of().strength(2.5F));
    public static final DeferredBlock<LaunchPadBlock> LAUNCH_PAD = BLOCKS.registerBlock(
            "launch_pad", LaunchPadBlock::new, BlockBehaviour.Properties.of().strength(3.0F));
    public static final DeferredBlock<PartConnectorBlock> STACK_INTERFACE = registerConnector("stack_interface", PartConnector.Kind.STACK);
    public static final DeferredBlock<PartConnectorBlock> SEPARATION_INTERFACE = registerConnector("separation_interface", PartConnector.Kind.SEPARATION);
    public static final DeferredBlock<PartConnectorBlock> DOCKING_INTERFACE = registerConnector("docking_interface", PartConnector.Kind.DOCKING);
    public static final DeferredBlock<PartConnectorBlock> ATTACHMENT_FACE = registerConnector("attachment_face", PartConnector.Kind.SURFACE);
    public static final DeferredBlock<CollisionCellBlock> SHIP_COLLISION_CELL = BLOCKS.registerBlock(
            "ship_collision_cell", properties -> ModList.get().isLoaded("sable")
                    ? SableCollisionCellBlock.create(properties) : new CollisionCellBlock(properties),
            BlockBehaviour.Properties.of().strength(-1.0F, 3_600_000.0F).noOcclusion().noLootTable());
    public static final DeferredItem<BakedPartItem> BAKED_PART = ITEMS.registerItem(
            "baked_part", BakedPartItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<ShipBlueprintItem> SHIP_BLUEPRINT = ITEMS.registerItem(
            "ship_blueprint", ShipBlueprintItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BakedPart>> PART_DATA =
            COMPONENTS.registerComponentType("part_data", builder -> builder.persistent(BakedPart.CODEC)
                    .networkSynchronized(BakedPart.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ShipDesign>> SHIP_DESIGN =
            COMPONENTS.registerComponentType("ship_design", builder -> builder.persistent(ShipDesign.CODEC)
                    .networkSynchronized(ShipDesign.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ShipDesign>> FLIGHT_LAYOUT =
            COMPONENTS.registerComponentType("flight_layout", builder -> builder.persistent(ShipDesign.FLIGHT_CODEC)
                    .networkSynchronized(ShipDesign.STREAM_CODEC));
    public static final DeferredHolder<EntityType<?>, EntityType<PlacedPartEntity>> PLACED_PART = ENTITIES.register(
            "placed_part", id -> EntityType.Builder.of(PlacedPartEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F).clientTrackingRange(10).build(id.toString()));
    public static final DeferredHolder<EntityType<?>, EntityType<AssembledShipEntity>> ASSEMBLED_SHIP = ENTITIES.register(
            "assembled_ship", id -> EntityType.Builder.of(AssembledShipEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F).clientTrackingRange(12).build(id.toString()));
    public static final DeferredHolder<MenuType<?>, MenuType<AssemblyMenu>> ASSEMBLY_MENU = MENUS.register(
            "assembly", () -> new MenuType<>(AssemblyMenu::new, FeatureFlags.DEFAULT_FLAGS));

    static {
        ITEMS.registerSimpleBlockItem(BAKING_TABLE);
        ITEMS.registerSimpleBlockItem(ASSEMBLY_TABLE);
        ITEMS.registerSimpleBlockItem(LAUNCH_PAD);
    }

    public BlockSpaceProgram(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, ShipDebrisConfig.SPEC);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        MENUS.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener(BlockPartProperties::register);
        modBus.addListener(PartConnector::register);
        modBus.addListener(AssemblyNetworking::register);
        modBus.addListener(ShipFlightInput::register);
        OrbitalNetworking.register(modBus);
        AssembledShipEntity.registerFlight();
        SkyNetworking.register(modBus);
        modBus.addListener(this::addCreativeTabItems);
        LogUtils.getLogger().info("BlockSpaceProgram loaded");
    }

    private void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.FUNCTIONAL_BLOCKS)) {
            event.accept(BAKING_TABLE);
            event.accept(ASSEMBLY_TABLE);
            event.accept(LAUNCH_PAD);
            event.accept(STACK_INTERFACE);
            event.accept(SEPARATION_INTERFACE);
            event.accept(DOCKING_INTERFACE);
            event.accept(ATTACHMENT_FACE);
            event.accept(SHIP_BLUEPRINT);
        }
    }

    private static DeferredBlock<PartConnectorBlock> registerConnector(String name, PartConnector.Kind kind) {
        var block = BLOCKS.registerBlock(name, properties -> new PartConnectorBlock(properties, kind),
                BlockBehaviour.Properties.of().strength(2.0F));
        ITEMS.registerSimpleBlockItem(block);
        return block;
    }
}

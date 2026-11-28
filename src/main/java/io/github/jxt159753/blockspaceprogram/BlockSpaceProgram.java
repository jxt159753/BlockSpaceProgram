package io.github.jxt159753.blockspaceprogram;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.AssemblyMenu;
import io.github.jxt159753.blockspaceprogram.assembly.AssemblyNetworking;
import io.github.jxt159753.blockspaceprogram.assembly.AssemblyTableBlock;
import io.github.jxt159753.blockspaceprogram.assembly.BakedPartItem;
import io.github.jxt159753.blockspaceprogram.assembly.FlightTerminalBlock;
import io.github.jxt159753.blockspaceprogram.assembly.FlightTerminalBlockEntity;
import io.github.jxt159753.blockspaceprogram.assembly.FlightTerminalMenu;
import io.github.jxt159753.blockspaceprogram.assembly.LaunchPadBlock;
import io.github.jxt159753.blockspaceprogram.assembly.PlacedPartEntity;
import io.github.jxt159753.blockspaceprogram.assembly.SableCollisionCellBlock;
import io.github.jxt159753.blockspaceprogram.assembly.ShipAssemblerItem;
import io.github.jxt159753.blockspaceprogram.assembly.ShipBlueprintItem;
import io.github.jxt159753.blockspaceprogram.assembly.ShipDesign;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightInput;
import io.github.jxt159753.blockspaceprogram.assembly.ShipServerConfig;
import io.github.jxt159753.blockspaceprogram.collision.CollisionCellBlock;
import io.github.jxt159753.blockspaceprogram.compat.computercraft.FlightPeripheral;
import io.github.jxt159753.blockspaceprogram.compat.ftbteams.FtbTeamsFacilityOwnerResolver;
import io.github.jxt159753.blockspaceprogram.compat.iris.IrisCompat;
import io.github.jxt159753.blockspaceprogram.facility.FacilityCommands;
import io.github.jxt159753.blockspaceprogram.facility.FacilityLevels;
import io.github.jxt159753.blockspaceprogram.facility.FacilityOwnerResolver;
import io.github.jxt159753.blockspaceprogram.orbit.BenchmarkNetworking;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalNetworking;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import io.github.jxt159753.blockspaceprogram.part.BakingTableBlock;
import io.github.jxt159753.blockspaceprogram.part.BlockPartProperties;
import io.github.jxt159753.blockspaceprogram.part.CombustionChamberBlock;
import io.github.jxt159753.blockspaceprogram.part.ModularEngines;
import io.github.jxt159753.blockspaceprogram.part.NozzleSegmentBlock;
import io.github.jxt159753.blockspaceprogram.part.PartConnector;
import io.github.jxt159753.blockspaceprogram.part.PartParachute;
import io.github.jxt159753.blockspaceprogram.part.PartConnectorBlock;
import io.github.jxt159753.blockspaceprogram.part.PartTemplate;
import io.github.jxt159753.blockspaceprogram.part.PartTemplateRecipe;
import io.github.jxt159753.blockspaceprogram.sky.SkyNetworking;
import io.github.jxt159753.blockspaceprogram.sky.SurfaceColor;
import java.util.function.BooleanSupplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.datamaps.AdvancedDataMapType;
import net.neoforged.neoforge.registries.datamaps.DataMapType;
import net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent;

@Mod(BlockSpaceProgram.MOD_ID)
public final class BlockSpaceProgram {
    public static final String MOD_ID = "blockspaceprogram";
    public static final ResourceKey<Level> ORBITAL_SPACE = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "orbital_space"));
    public static final DataMapType<Biome, SurfaceColor> SURFACE_COLOR = AdvancedDataMapType
            .builder(ResourceLocation.fromNamespaceAndPath(MOD_ID, "surface_color"), Registries.BIOME, SurfaceColor.CODEC)
            .merger((registry, first, firstValue, second, secondValue) ->
                    SurfaceColor.merge(firstValue, first.right().isPresent(), secondValue, second.right().isPresent()))
            .synced(SurfaceColor.CODEC, false)
            .build();

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);
    private static final DeferredRegister<RecipeSerializer<?>> RECIPES = DeferredRegister.create(Registries.RECIPE_SERIALIZER, MOD_ID);
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MOD_ID);
    private static FacilityLevels facilities;
    private static BooleanSupplier irisShaderPackInUse = () -> false;

    public static final DeferredBlock<BakingTableBlock> BAKING_TABLE = BLOCKS.registerBlock(
            "baking_table", BakingTableBlock::new, BlockBehaviour.Properties.of().strength(2.5F));
    public static final DeferredBlock<AssemblyTableBlock> ASSEMBLY_TABLE = BLOCKS.registerBlock(
            "assembly_table", AssemblyTableBlock::new, BlockBehaviour.Properties.of().strength(2.5F));
    public static final DeferredBlock<LaunchPadBlock> LAUNCH_PAD = BLOCKS.registerBlock(
            "launch_pad", LaunchPadBlock::new, BlockBehaviour.Properties.of().strength(3.0F));
    public static final DeferredBlock<FlightTerminalBlock> FLIGHT_TERMINAL = BLOCKS.registerBlock(
            "flight_terminal", FlightTerminalBlock::new, BlockBehaviour.Properties.of().strength(2.5F));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FlightTerminalBlockEntity>> FLIGHT_TERMINAL_ENTITY =
            BLOCK_ENTITIES.register("flight_terminal",
                    () -> BlockEntityType.Builder.of(FlightTerminalBlockEntity::new, FLIGHT_TERMINAL.get()).build(null));
    public static final DeferredBlock<PartConnectorBlock> STACK_INTERFACE = registerConnector("stack_interface", PartConnector.Kind.STACK);
    public static final DeferredBlock<PartConnectorBlock> SEPARATION_INTERFACE = registerConnector("separation_interface", PartConnector.Kind.SEPARATION);
    public static final DeferredBlock<PartConnectorBlock> DOCKING_INTERFACE = registerConnector("docking_interface", PartConnector.Kind.DOCKING);
    public static final DeferredBlock<PartConnectorBlock> ATTACHMENT_FACE = registerConnector("attachment_face", PartConnector.Kind.SURFACE);
    public static final DeferredBlock<CombustionChamberBlock> COMBUSTION_CHAMBER = BLOCKS.registerBlock(
            "combustion_chamber", CombustionChamberBlock::new, BlockBehaviour.Properties.of().strength(3.0F).noOcclusion());
    public static final DeferredBlock<NozzleSegmentBlock> NOZZLE_SEGMENT = BLOCKS.registerBlock(
            "nozzle_segment", NozzleSegmentBlock::new, BlockBehaviour.Properties.of().strength(2.5F).noOcclusion());
    public static final DeferredBlock<Block> PARACHUTE = BLOCKS.registerBlock(
            "parachute", Block::new, BlockBehaviour.Properties.of().strength(2.0F).noOcclusion());
    public static final DeferredBlock<Block> FAIRING_PANEL = BLOCKS.registerSimpleBlock(
            "fairing_panel", BlockBehaviour.Properties.of().strength(2.0F));
    public static final DeferredBlock<Block> HEAT_SHIELD = BLOCKS.registerSimpleBlock(
            "heat_shield", BlockBehaviour.Properties.of().strength(3.0F, 6.0F).requiresCorrectToolForDrops());
    public static final DeferredBlock<CollisionCellBlock> SHIP_COLLISION_CELL = BLOCKS.registerBlock(
            "ship_collision_cell", properties -> ModList.get().isLoaded("sable")
                    ? SableCollisionCellBlock.create(properties) : new CollisionCellBlock(properties),
            BlockBehaviour.Properties.of().strength(-1.0F, 3_600_000.0F).noOcclusion().noLootTable());
    public static final DeferredItem<BakedPartItem> BAKED_PART = ITEMS.registerItem(
            "baked_part", BakedPartItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<ShipBlueprintItem> SHIP_BLUEPRINT = ITEMS.registerItem(
            "ship_blueprint", ShipBlueprintItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredItem<Item> SHIP_ASSEMBLER = ITEMS.registerItem(
            "ship_assembler", ShipAssemblerItem::new, new Item.Properties().stacksTo(1));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BakedPart>> PART_DATA =
            COMPONENTS.registerComponentType("part_data", builder -> builder.persistent(BakedPart.CODEC)
                    .networkSynchronized(BakedPart.STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ShipDesign>> SHIP_DESIGN =
            COMPONENTS.registerComponentType("ship_design", builder -> builder.persistent(ShipDesign.CODEC)
                    .networkSynchronized(ShipDesign.FLIGHT_STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ShipDesign>> FLIGHT_LAYOUT =
            COMPONENTS.registerComponentType("flight_layout", builder -> builder.persistent(ShipDesign.FLIGHT_CODEC)
                    .networkSynchronized(ShipDesign.FLIGHT_STREAM_CODEC));
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ShipDesign>> BENCHMARK_LAYOUT =
            COMPONENTS.registerComponentType("benchmark_layout",
                    builder -> builder.networkSynchronized(ShipDesign.BENCHMARK_STREAM_CODEC));
    public static final DeferredHolder<EntityType<?>, EntityType<PlacedPartEntity>> PLACED_PART = ENTITIES.register(
            "placed_part", id -> EntityType.Builder.of(PlacedPartEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F).clientTrackingRange(10).build(id.toString()));
    public static final DeferredHolder<EntityType<?>, EntityType<AssembledShipEntity>> ASSEMBLED_SHIP = ENTITIES.register(
            "assembled_ship", id -> EntityType.Builder.of(AssembledShipEntity::new, MobCategory.MISC)
                    .sized(1.0F, 1.0F).clientTrackingRange(12).build(id.toString()));
    public static final DeferredHolder<MenuType<?>, MenuType<AssemblyMenu>> ASSEMBLY_MENU = MENUS.register(
            "assembly", () -> IMenuTypeExtension.create(AssemblyMenu::tableClient));
    public static final DeferredHolder<MenuType<?>, MenuType<AssemblyMenu>> WORLD_ASSEMBLY_MENU = MENUS.register(
            "world_assembly", () -> IMenuTypeExtension.create(AssemblyMenu::worldClient));
    public static final DeferredHolder<MenuType<?>, MenuType<FlightTerminalMenu>> FLIGHT_TERMINAL_MENU = MENUS.register(
            "flight_terminal", () -> IMenuTypeExtension.create(FlightTerminalMenu::new));
    public static final DeferredHolder<RecipeSerializer<?>, PartTemplateRecipe.Serializer> PART_TEMPLATE_RECIPE =
            RECIPES.register("part_template", PartTemplateRecipe.Serializer::new);

    static {
        ITEMS.registerSimpleBlockItem(BAKING_TABLE);
        ITEMS.registerSimpleBlockItem(ASSEMBLY_TABLE);
        ITEMS.registerSimpleBlockItem(LAUNCH_PAD);
        ITEMS.registerSimpleBlockItem(FLIGHT_TERMINAL);
        ITEMS.registerSimpleBlockItem(COMBUSTION_CHAMBER);
        ITEMS.registerSimpleBlockItem(NOZZLE_SEGMENT);
        ITEMS.registerSimpleBlockItem(PARACHUTE);
        ITEMS.registerSimpleBlockItem(FAIRING_PANEL);
        ITEMS.registerSimpleBlockItem(HEAT_SHIELD);
        CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.blockspaceprogram.main"))
                .icon(() -> new ItemStack(LAUNCH_PAD.get()))
                .displayItems((parameters, output) -> {
                    output.accept(BAKING_TABLE);
                    output.accept(STACK_INTERFACE);
                    output.accept(SEPARATION_INTERFACE);
                    output.accept(DOCKING_INTERFACE);
                    output.accept(ATTACHMENT_FACE);
                    output.accept(COMBUSTION_CHAMBER);
                    output.accept(NOZZLE_SEGMENT);
                    output.accept(PARACHUTE);
                    output.accept(FAIRING_PANEL);
                    output.accept(HEAT_SHIELD);
                    ItemStack largeEngine = PartTemplate.creativeLargeEngine(parameters.holders());
                    if (!largeEngine.isEmpty()) {
                        output.accept(largeEngine);
                    }
                    output.accept(ASSEMBLY_TABLE);
                    output.accept(SHIP_BLUEPRINT);
                    output.accept(SHIP_ASSEMBLER);
                    output.accept(LAUNCH_PAD);
                    output.accept(FLIGHT_TERMINAL);
                })
                .build());
    }

    public BlockSpaceProgram(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, ShipServerConfig.SPEC);
        facilities = new FacilityLevels(ShipServerConfig.FACILITY, ModList.get().isLoaded("ftbteams")
                ? new FtbTeamsFacilityOwnerResolver() : FacilityOwnerResolver::personal);
        NeoForge.EVENT_BUS.addListener(FacilityCommands::register);
        NeoForge.EVENT_BUS.addListener(PartTemplate::register);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        CREATIVE_TABS.register(modBus);
        ENTITIES.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        RECIPES.register(modBus);
        COMPONENTS.register(modBus);
        modBus.addListener(BlockPartProperties::register);
        modBus.addListener(ModularEngines::register);
        modBus.addListener(PartParachute::register);
        modBus.addListener(PartConnector::register);
        modBus.addListener((RegisterDataMapTypesEvent event) -> event.register(SURFACE_COLOR));
        modBus.addListener(AssemblyNetworking::register);
        modBus.addListener(ShipFlightInput::register);
        modBus.addListener(BenchmarkNetworking::register);
        OrbitalNetworking.register(modBus);
        AssembledShipEntity.registerFlight();
        if (ModList.get().isLoaded("computercraft")) {
            FlightPeripheral.register(modBus);
        }
        if (ModList.get().isLoaded("iris")) {
            irisShaderPackInUse = IrisCompat::isShaderPackInUse;
        }
        SkyNetworking.register(modBus);
        LogUtils.getLogger().info("BlockSpaceProgram loaded");
    }

    public static FacilityLevels facilities() {
        return facilities;
    }

    public static boolean isIrisShaderPackInUse() {
        return irisShaderPackInUse.getAsBoolean();
    }

    private static DeferredBlock<PartConnectorBlock> registerConnector(String name, PartConnector.Kind kind) {
        var block = BLOCKS.registerBlock(name, properties -> new PartConnectorBlock(properties, kind),
                BlockBehaviour.Properties.of().strength(2.0F));
        ITEMS.registerSimpleBlockItem(block);
        return block;
    }
}

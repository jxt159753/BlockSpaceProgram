package io.github.jxt159753.blockspaceprogram.part;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jetbrains.annotations.Nullable;

public final class EngineTemplates {
    private static final ResourceKey<CreativeModeTab> CREATIVE_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "main"));
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, BlockSpaceProgram.MOD_ID);
    static final DeferredHolder<RecipeSerializer<?>, PartTemplateRecipe.Serializer> RECIPE =
            SERIALIZERS.register("part_template", PartTemplateRecipe.Serializer::new);
    private static volatile Map<ResourceLocation, PartTemplate> templates = Map.of();

    private EngineTemplates() {}

    public static void register(IEventBus modBus, DeferredRegister.Blocks blocks, DeferredRegister.Items items) {
        var chamber = blocks.registerSimpleBlock("combustion_chamber", BlockBehaviour.Properties.of().strength(2));
        var segment = blocks.registerBlock("nozzle_segment", NozzleSegmentBlock::new,
                BlockBehaviour.Properties.of().strength(2).noOcclusion());
        items.registerSimpleBlockItem(chamber);
        items.registerSimpleBlockItem(segment);
        SERIALIZERS.register(modBus);
        modBus.addListener(ModularEngines::register);
        modBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey().equals(CREATIVE_TAB)) {
                event.accept(chamber);
                event.accept(segment);
            }
        });
        NeoForge.EVENT_BUS.addListener((AddReloadListenerEvent event) -> event.addListener(new Loader()));
    }

    @Nullable
    static PartTemplate find(ResourceLocation id) {
        return templates.get(id);
    }

    private static final class Loader extends SimpleJsonResourceReloadListener {
        private Loader() {
            super(new Gson(), "part_template");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> jsons, ResourceManager resources, ProfilerFiller profiler) {
            var loaded = new HashMap<ResourceLocation, PartTemplate>();
            jsons.forEach((id, json) -> PartTemplate.CODEC.parse(JsonOps.INSTANCE, json)
                    .resultOrPartial(error -> LogUtils.getLogger().warn("Invalid part template {}: {}", id, error))
                    .ifPresent(template -> loaded.put(id, template)));
            templates = Map.copyOf(loaded);
        }
    }
}

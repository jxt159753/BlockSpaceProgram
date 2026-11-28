package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public final class PartTemplateRecipe extends ShapelessRecipe {
    private final ResourceLocation template;
    @Nullable private final ItemStack syncedResult;

    private PartTemplateRecipe(String group, CraftingBookCategory category, List<Ingredient> ingredients, ResourceLocation template) {
        this(group, category, ingredients, template, null);
    }

    private PartTemplateRecipe(String group, CraftingBookCategory category, List<Ingredient> ingredients,
            ResourceLocation template, @Nullable ItemStack syncedResult) {
        super(group, category, ItemStack.EMPTY, NonNullList.copyOf(ingredients));
        this.template = template;
        this.syncedResult = syncedResult;
    }

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return result();
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return super.matches(input, level)
                && (syncedResult == null ? PartTemplate.find(template).isPresent() : !syncedResult.isEmpty());
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return result();
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return BlockSpaceProgram.PART_TEMPLATE_RECIPE.get();
    }

    private ItemStack result() {
        // The client gets a complete result for the recipe book. Server crafting resolves the current template.
        return syncedResult != null ? syncedResult.copy()
                : PartTemplate.find(template).map(PartTemplate::stack).orElse(ItemStack.EMPTY);
    }

    public static final class Serializer implements RecipeSerializer<PartTemplateRecipe> {
        private static final MapCodec<PartTemplateRecipe> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.optionalFieldOf("group", "").forGetter(PartTemplateRecipe::getGroup),
                CraftingBookCategory.CODEC.optionalFieldOf("category", CraftingBookCategory.MISC)
                        .forGetter(PartTemplateRecipe::category),
                Ingredient.CODEC_NONEMPTY.listOf(1, 9).fieldOf("ingredients").forGetter(PartTemplateRecipe::getIngredients),
                ResourceLocation.CODEC.fieldOf("template").forGetter(recipe -> recipe.template)
        ).apply(instance, PartTemplateRecipe::new));

        private static final StreamCodec<RegistryFriendlyByteBuf, List<Ingredient>> INGREDIENTS =
                Ingredient.CONTENTS_STREAM_CODEC.apply(ByteBufCodecs.list(9));
        private static final StreamCodec<RegistryFriendlyByteBuf, PartTemplateRecipe> STREAM_CODEC = StreamCodec.of(
                (buffer, recipe) -> {
                    buffer.writeUtf(recipe.getGroup());
                    buffer.writeEnum(recipe.category());
                    INGREDIENTS.encode(buffer, recipe.getIngredients());
                    buffer.writeResourceLocation(recipe.template);
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, recipe.result());
                }, buffer -> new PartTemplateRecipe(buffer.readUtf(), buffer.readEnum(CraftingBookCategory.class),
                        INGREDIENTS.decode(buffer), buffer.readResourceLocation(), ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer)));

        @Override
        public MapCodec<PartTemplateRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, PartTemplateRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}

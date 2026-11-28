package io.github.jxt159753.blockspaceprogram.part;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
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

final class PartTemplateRecipe extends ShapelessRecipe {
    private final ResourceLocation template;
    // The server sends the resulting snapshot with the recipe so the client need not read server data packs.
    @Nullable private final ItemStack syncedResult;

    PartTemplateRecipe(String group, CraftingBookCategory category, List<Ingredient> ingredients, ResourceLocation template) {
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
        return super.matches(input, level) && !result().isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return result();
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return EngineTemplates.RECIPE.get();
    }

    private ItemStack result() {
        if (syncedResult != null) {
            return syncedResult.copy();
        }
        PartTemplate definition = EngineTemplates.find(template);
        return definition == null ? ItemStack.EMPTY : definition.createItem();
    }

    static final class Serializer implements RecipeSerializer<PartTemplateRecipe> {
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

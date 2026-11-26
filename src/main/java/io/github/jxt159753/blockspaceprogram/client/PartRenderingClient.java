package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.part.PlacedPartEntity;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class PartRenderingClient {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(BlockSpaceProgram.PLACED_PART.get(), PlacedPartRenderer::new);
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent event) throws IOException {
        var id = ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "part_cutout");
        event.registerShader(new ShaderInstance(event.getResourceProvider(), id, DefaultVertexFormat.NEW_ENTITY),
                PartMeshRenderer::setCutoutShader);
    }

    @SubscribeEvent
    public static void registerItemRenderer(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            private PartItemRenderer renderer;

            @Override
            public BlockEntityWithoutLevelRenderer getCustomRenderer() {
                if (renderer == null) {
                    renderer = new PartItemRenderer(Minecraft.getInstance());
                }
                return renderer;
            }
        }, BlockSpaceProgram.BAKED_PART);
    }

    @SubscribeEvent
    public static void useCustomItemRenderer(ModelEvent.ModifyBakingResult event) {
        ModelResourceLocation id = ModelResourceLocation.inventory(BlockSpaceProgram.BAKED_PART.getId());
        event.getModels().computeIfPresent(id, (key, model) -> new BakedModelWrapper<>(model) {
            @Override
            public boolean isCustomRenderer() {
                return true;
            }

            @Override
            public BakedModel applyTransform(ItemDisplayContext context, PoseStack poses, boolean leftHand) {
                originalModel.applyTransform(context, poses, leftHand);
                return this;
            }
        });
    }

    @SubscribeEvent
    public static void clearMeshes(ModelEvent.BakingCompleted event) {
        PartMeshRenderer.clear();
    }

    private static final class PartItemRenderer extends BlockEntityWithoutLevelRenderer {
        private PartItemRenderer(Minecraft game) {
            super(game.getBlockEntityRenderDispatcher(), game.getEntityModels());
        }

        @Override
        public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack poses, MultiBufferSource buffers,
                int light, int overlay) {
            var part = stack.get(BlockSpaceProgram.PART_DATA);
            if (part != null) {
                PartMeshRenderer.renderItem(part, poses, buffers, light, overlay);
            } else {
                Minecraft.getInstance().getBlockRenderer().renderSingleBlock(Blocks.CUT_COPPER.defaultBlockState(),
                        poses, buffers, light, overlay, ModelData.EMPTY, null);
            }
        }
    }

    private static final class PlacedPartRenderer extends EntityRenderer<PlacedPartEntity> {
        private PlacedPartRenderer(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public void render(PlacedPartEntity entity, float yaw, float partialTick, PoseStack poses,
                MultiBufferSource buffers, int light) {
            var part = entity.part();
            if (part != null) {
                PartMeshRenderer.render(part, poses, buffers, light);
                CollisionDebugClient.renderPlacedPart(part, poses, buffers);
            }
            super.render(entity, yaw, partialTick, poses, buffers, light);
        }

        @Override
        public ResourceLocation getTextureLocation(PlacedPartEntity entity) {
            return TextureAtlas.LOCATION_BLOCKS;
        }
    }
}

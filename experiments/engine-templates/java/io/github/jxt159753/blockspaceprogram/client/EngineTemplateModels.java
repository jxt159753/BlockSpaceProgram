package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
final class EngineTemplateModels {
    private EngineTemplateModels() {}

    @SubscribeEvent
    static void register(ModelEvent.RegisterAdditional event) {
        var resources = Minecraft.getInstance().getResourceManager();
        for (ResourceLocation file : resources.listResources("models/part", id -> id.getPath().endsWith(".json")).keySet()) {
            ResourceLocation model = file.withPath(path -> path.substring("models/".length(), path.length() - ".json".length()));
            event.register(ModelResourceLocation.standalone(model));
        }
    }

    static void bake(ResourceLocation id, Vec3 size, PoseStack poses, VertexConsumer cutout, VertexConsumer translucent) {
        var game = Minecraft.getInstance();
        var model = game.getModelManager().getModel(ModelResourceLocation.standalone(id));
        var renderer = game.getBlockRenderer().getModelRenderer();
        var state = Blocks.BARRIER.defaultBlockState();
        poses.pushPose();
        poses.scale((float) size.x, (float) size.y, (float) size.z);
        for (RenderType layer : model.getRenderTypes(state, RandomSource.create(0), ModelData.EMPTY)) {
            var target = layer == RenderType.translucent() ? translucent : cutout;
            renderer.renderModel(poses.last(), target, state, model, 1, 1, 1,
                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, layer);
        }
        poses.popPose();
    }
}

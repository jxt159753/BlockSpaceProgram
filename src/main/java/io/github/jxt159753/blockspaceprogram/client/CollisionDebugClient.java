package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class CollisionDebugClient {
    private static final KeyMapping TOGGLE = new KeyMapping("key.blockspaceprogram.collision",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "key.categories.blockspaceprogram");
    private static boolean visible;

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var player = Minecraft.getInstance().player;
        while (TOGGLE.consumeClick()) {
            if (player != null) {
                visible = !visible;
                player.displayClientMessage(Component.translatable(visible
                        ? "debug.blockspaceprogram.collision.on" : "debug.blockspaceprogram.collision.off"), true);
            }
        }
    }

    static void renderPlacedPart(BakedPart part, PoseStack poses, MultiBufferSource buffers) {
        if (visible) {
            LevelRenderer.renderLineBox(poses, buffers.getBuffer(RenderType.lines()), part.localBounds().inflate(0.002),
                    0.2F, 1.0F, 0.4F, 1.0F);
        }
    }

    @SubscribeEvent
    public static void renderShipCells(RenderLevelStageEvent event) {
        if (visible && event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES
                && ModList.get().isLoaded("sable")) {
            SableCollisionDebugRenderer.render(event);
        }
    }
}

package io.github.jxt159753.blockspaceprogram.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import io.github.jxt159753.blockspaceprogram.client.PlanetSkyClient;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Shadow private ClientLevel level;
    @Shadow private VertexBuffer starBuffer;

    @Inject(method = "renderSky", at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V", ordinal = 0),
            cancellable = true)
    private void bsp$renderPlanetSky(Matrix4f view, Matrix4f projection, float partialTick, Camera camera,
                                     boolean isFoggy, Runnable setupFog, CallbackInfo callback) {
        if (PlanetSkyClient.render(level, camera, partialTick, view, projection, starBuffer, isFoggy)) {
            callback.cancel();
        }
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void bsp$hideVacuumClouds(PoseStack poses, Matrix4f view, Matrix4f projection, float partialTick,
                                      double x, double y, double z, CallbackInfo callback) {
        if (PlanetSkyClient.vacuum(level, y)) {
            callback.cancel();
        }
    }
}

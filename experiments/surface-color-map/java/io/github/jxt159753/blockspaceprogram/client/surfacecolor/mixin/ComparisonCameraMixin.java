package io.github.jxt159753.blockspaceprogram.client.surfacecolor.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import io.github.jxt159753.blockspaceprogram.client.surfacecolor.SurfaceColorMapClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = GameRenderer.class, remap = false)
abstract class ComparisonCameraMixin {
    @ModifyReturnValue(method = "getDepthFar", at = @At("RETURN"))
    private float bsp$extendFarPlaneForComparison(float far) {
        // At Y=3000 even a 32-chunk view distance clips the terrain below the camera.
        return SurfaceColorMapClient.COMPARE_TERRAIN ? Math.max(far, SurfaceColorMapClient.COMPARISON_DISTANCE) : far;
    }
}

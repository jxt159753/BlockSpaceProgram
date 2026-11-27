package io.github.jxt159753.blockspaceprogram.client.surfacecolor.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.jxt159753.blockspaceprogram.client.surfacecolor.SurfaceColorMapClient;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = SectionOcclusionGraph.class, remap = false)
abstract class ComparisonChunkCullingMixin {
    @ModifyExpressionValue(method = "getRelativeFrom", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;abs(I)I"))
    private int bsp$includeLoadedSectionsBelowCamera(int verticalDistance) {
        return SurfaceColorMapClient.COMPARE_TERRAIN ? 0 : verticalDistance;
    }
}

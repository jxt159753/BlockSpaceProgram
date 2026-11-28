package io.github.jxt159753.blockspaceprogram.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.jxt159753.blockspaceprogram.client.SableShipCamera;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = Camera.class, priority = 1100)
abstract class ShipCameraZoomMixin {
    @Dynamic("The zoom setter is added by Sable 2.0.6")
    @WrapOperation(method = "sable$setZoomAmount", remap = false, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F"))
    private float blockspaceprogram$clampShipZoom(float value, float min, float max, Operation<Float> original) {
        if (ModList.get().isLoaded("sable") && SableShipCamera.usesShipView(Minecraft.getInstance())) {
            return SableShipCamera.clampZoom(value);
        }
        return original.call(value, min, max);
    }
}

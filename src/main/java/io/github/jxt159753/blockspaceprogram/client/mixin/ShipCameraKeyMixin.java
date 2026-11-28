package io.github.jxt159753.blockspaceprogram.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.jxt159753.blockspaceprogram.client.SableShipCamera;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

@Mixin(Minecraft.class)
abstract class ShipCameraKeyMixin {
    @ModifyExpressionValue(method = "handleKeybinds",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;consumeClick()Z", ordinal = 0),
            slice = @Slice(from = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/Options;keyTogglePerspective:Lnet/minecraft/client/KeyMapping;")))
    private boolean blockspaceprogram$cycleShipCamera(boolean perspectiveClick) {
        if (ModList.get().isLoaded("sable")) {
            return SableShipCamera.cyclePerspective((Minecraft) (Object) this, perspectiveClick);
        }
        return perspectiveClick;
    }
}

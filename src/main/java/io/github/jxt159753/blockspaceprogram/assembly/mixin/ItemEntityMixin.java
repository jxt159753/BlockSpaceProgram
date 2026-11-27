package io.github.jxt159753.blockspaceprogram.assembly.mixin;

import io.github.jxt159753.blockspaceprogram.assembly.PlanetEntityGravity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ItemEntity.class)
abstract class ItemEntityMixin {
    @Inject(method = "getDefaultGravity", at = @At("RETURN"), cancellable = true)
    private void scaleGravity(CallbackInfoReturnable<Double> callback) {
        double scale = PlanetEntityGravity.scale(((ItemEntity) (Object) this).level());
        if (scale != 1) {
            callback.setReturnValue(callback.getReturnValueD() * scale);
        }
    }
}

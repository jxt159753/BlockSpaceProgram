package io.github.jxt159753.blockspaceprogram.client.mixin;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.client.PlanetSkyClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelTimeAccess;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin implements LevelTimeAccess {
    // Vanilla uses the cosine of this angle for its lightmap, cloud color, and fog.
    @Override
    public float getTimeOfDay(float partialTick) {
        return PlanetSkyClient.solarTime((ClientLevel) (Object) this, partialTick);
    }

    // Iris reads getDayTime for worldTime; dayTime remains the synchronized vanilla clock.
    public long getDayTime() {
        return PlanetSkyClient.shaderDayTime((ClientLevel) (Object) this);
    }

    @Inject(method = "getSkyColor", at = @At("HEAD"), cancellable = true)
    private void bsp$vacuumSkyColor(Vec3 position, float partialTick, CallbackInfoReturnable<Vec3> callback) {
        if (BlockSpaceProgram.isIrisShaderPackInUse() && PlanetSkyClient.vacuum((ClientLevel) (Object) this, position.y)) {
            callback.setReturnValue(Vec3.ZERO);
        }
    }
}

package io.github.jxt159753.blockspaceprogram.client.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface CameraEyeHeightAccessor {
    @Accessor("eyeHeight")
    void blockspaceprogram$setEyeHeight(float height);

    @Accessor("eyeHeightOld")
    void blockspaceprogram$setOldEyeHeight(float height);
}

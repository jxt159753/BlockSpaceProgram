package io.github.jxt159753.blockspaceprogram.client.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface LevelRendererSkyAccessor {
    @Accessor("starBuffer")
    VertexBuffer blockspaceprogram$getStarBuffer();
}

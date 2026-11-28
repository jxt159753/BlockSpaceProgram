package io.github.jxt159753.blockspaceprogram.client.surfacecolor.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.jxt159753.blockspaceprogram.client.PlanetSkyClient;
import io.github.jxt159753.blockspaceprogram.client.surfacecolor.SurfaceColorMapClient;
import io.github.jxt159753.blockspaceprogram.orbit.CelestialBody;
import io.github.jxt159753.blockspaceprogram.sky.surfacecolor.SurfaceColorMapNetworking;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PlanetSkyClient.class, remap = false)
abstract class PlanetSkyColorMixin {
    @Shadow
    private static ShaderInstance shader;

    // Each disc retains its own local prime meridian vector through the distance sort.
    @Unique
    private static final Map<Vec3, CelestialBody> bsp$bodyByMeridian = new IdentityHashMap<>();

    @ModifyArg(method = "registerShader", index = 1, at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/ShaderInstance;<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;"
                    + "Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V"))
    private static ResourceLocation bsp$useMapShader(ResourceLocation original) {
        return SurfaceColorMapNetworking.id("surface_map");
    }

    @ModifyExpressionValue(method = "renderBodies", at = @At(value = "INVOKE", ordinal = 1, target =
            "Lio/github/jxt159753/blockspaceprogram/client/PlanetSkyProjection$Observer;toLocal"
                    + "(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"))
    private static Vec3 bsp$identifyDisc(Vec3 primeMeridian, @Local CelestialBody body) {
        bsp$bodyByMeridian.put(primeMeridian, body);
        return primeMeridian;
    }

    @Inject(method = "draw", at = @At("HEAD"))
    private static void bsp$bindSurfaceMap(int mode, Vec3 direction, double relativeRadius, Vec3 primeMeridian,
                                           int seed, CallbackInfo ci) {
        var body = bsp$bodyByMeridian.remove(primeMeridian);
        var texture = body == null ? null : SurfaceColorMapClient.texture(body.id());
        float blend = texture == null ? 0 : SurfaceColorMapClient.mapBlend(body.radius() * (1 / relativeRadius - 1));
        shader.safeGetUniform("SurfaceMapBlend").set(blend);
        shader.setSampler("SurfaceMap", blend == 0 ? null : texture);
    }
}

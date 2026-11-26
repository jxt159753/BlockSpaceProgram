package io.github.jxt159753.blockspaceprogram.client.mixin;

import io.github.jxt159753.blockspaceprogram.client.PlanetSkyClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelTimeAccess;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientLevel.class)
abstract class ClientLevelMixin implements LevelTimeAccess {
    // Vanilla uses the cosine of this angle for its lightmap, cloud color, and fog.
    @Override
    public float getTimeOfDay(float partialTick) {
        return PlanetSkyClient.solarTime((ClientLevel) (Object) this, partialTick);
    }
}

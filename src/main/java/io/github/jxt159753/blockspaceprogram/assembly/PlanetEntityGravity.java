package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

public final class PlanetEntityGravity {
    private static final double VANILLA_SURFACE_GRAVITY = 9.8;
    private static final ResourceLocation MODIFIER = ResourceLocation.fromNamespaceAndPath(
            BlockSpaceProgram.MOD_ID, "planet_gravity");

    private PlanetEntityGravity() {}

    static void register() {
        NeoForge.EVENT_BUS.addListener(PlanetEntityGravity::onJoinLevel);
        NeoForge.EVENT_BUS.addListener(PlanetEntityGravity::onDatapackSync);
    }

    public static double scale(Level level) {
        var planet = PlanetFlightProperties.in(level);
        return planet == null ? 1 : planet.surfaceGravity() / VANILLA_SURFACE_GRAVITY;
    }

    private static void onJoinLevel(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof LivingEntity living) {
            apply(living, scale(event.getLevel()));
        }
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() == null) {
            for (var level : event.getPlayerList().getServer().getAllLevels()) {
                double scale = scale(level);
                for (var entity : level.getAllEntities()) {
                    if (entity instanceof LivingEntity living) {
                        apply(living, scale);
                    }
                }
            }
        }
    }

    private static void apply(LivingEntity living, double scale) {
        if (scale == 1 && !living.getAttributes().hasModifier(Attributes.GRAVITY, MODIFIER)) {
            return;
        }
        var gravity = living.getAttribute(Attributes.GRAVITY);
        if (gravity == null) {
            return;
        }
        if (scale == 1) {
            gravity.removeModifier(MODIFIER);
        } else {
            gravity.addOrUpdateTransientModifier(new AttributeModifier(MODIFIER, scale - 1,
                    AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }
}

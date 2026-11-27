package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData.ManeuverResult;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class OrbitalMapClient {
    private static final KeyMapping MAP = new KeyMapping("key.blockspaceprogram.orbital_map",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.blockspaceprogram");

    private OrbitalMapClient() {}

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(MAP);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        while (MAP.consumeClick()) {
            if (game.player != null && game.screen == null && ClientOrbitalState.hasClock()) {
                game.setScreen(new OrbitalMapScreen());
            }
        }
    }

    static boolean isMapKey(int key, int scanCode) {
        return MAP.matches(key, scanCode);
    }

    public static void receiveResult(UUID id, ManeuverResult result) {
        if (Minecraft.getInstance().screen instanceof OrbitalMapScreen screen) {
            screen.receiveResult(id, result);
        }
    }
}

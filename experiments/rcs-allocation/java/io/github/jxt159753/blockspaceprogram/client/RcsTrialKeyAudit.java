package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.Arrays;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class RcsTrialKeyAudit {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean checked;

    private RcsTrialKeyAudit() {}

    @SubscribeEvent
    public static void audit(ClientTickEvent.Post event) {
        if (checked) {
            return;
        }
        checked = true;
        int[] keys = {GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT,
                GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN, GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_O};
        for (int key : keys) {
            var input = InputConstants.Type.KEYSYM.getOrCreate(key);
            var conflicts = Arrays.stream(Minecraft.getInstance().options.keyMappings)
                    .filter(mapping -> mapping.getDefaultKey().equals(input)).map(KeyMapping::getName).sorted().toList();
            LOGGER.info("RCS KEY default={} matches={}", input.getName(), conflicts);
        }
    }
}

package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.ShipFlightInput;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import static io.github.jxt159753.blockspaceprogram.assembly.ShipFlightInput.*;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class ShipFlightClient {
    private static final KeyMapping ROLL_LEFT_KEY = key("roll_left", GLFW.GLFW_KEY_Z);
    private static final KeyMapping ROLL_RIGHT_KEY = key("roll_right", GLFW.GLFW_KEY_C);
    private static final KeyMapping THROTTLE_UP_KEY = key("throttle_up", GLFW.GLFW_KEY_R);
    private static final KeyMapping THROTTLE_DOWN_KEY = key("throttle_down", GLFW.GLFW_KEY_V);
    private static final KeyMapping CUTOFF_KEY = key("cutoff", GLFW.GLFW_KEY_X);
    private static final KeyMapping ATTITUDE_KEY = key("cycle_attitude", GLFW.GLFW_KEY_J);
    private static final KeyMapping THROTTLE_MODE_KEY = key("cycle_throttle", GLFW.GLFW_KEY_K);

    private static KeyMapping key(String name, int keyCode) {
        return new KeyMapping("key.blockspaceprogram." + name, InputConstants.Type.KEYSYM, keyCode, "key.categories.blockspaceprogram");
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(ROLL_LEFT_KEY);
        event.register(ROLL_RIGHT_KEY);
        event.register(THROTTLE_UP_KEY);
        event.register(THROTTLE_DOWN_KEY);
        event.register(CUTOFF_KEY);
        event.register(ATTITUDE_KEY);
        event.register(THROTTLE_MODE_KEY);
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(BlockSpaceProgram.FLIGHT_TERMINAL_MENU.get(), FlightTerminalScreen::new);
    }

    @SubscribeEvent
    public static void sendInput(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        if (game.player == null || !(game.player.getVehicle() instanceof AssembledShipEntity ship) || !ship.isFlyingShip()) {
            return;
        }
        var options = game.options;
        int keys = 0;
        if (game.screen == null) {
            keys = flag(options.keyUp, PITCH_UP) | flag(options.keyDown, PITCH_DOWN)
                    | flag(options.keyLeft, YAW_LEFT) | flag(options.keyRight, YAW_RIGHT)
                    | flag(ROLL_LEFT_KEY, ROLL_LEFT) | flag(ROLL_RIGHT_KEY, ROLL_RIGHT)
                    | flag(THROTTLE_UP_KEY, THROTTLE_UP) | flag(THROTTLE_DOWN_KEY, THROTTLE_DOWN) | flag(CUTOFF_KEY, CUTOFF)
                    | flag(options.keyJump, STAGE) | flag(ATTITUDE_KEY, CYCLE_ATTITUDE) | flag(THROTTLE_MODE_KEY, CYCLE_THROTTLE);
        }
        PacketDistributor.sendToServer(new ShipFlightInput(ship.getId(), keys));
    }

    private static int flag(KeyMapping mapping, int key) {
        return mapping.isDown() ? key : 0;
    }

    @SubscribeEvent
    public static void registerHud(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath(BlockSpaceProgram.MOD_ID, "flight_status"),
                (graphics, tick) -> {
                    var game = Minecraft.getInstance();
                    if (!game.options.hideGui && game.player != null
                            && game.player.getVehicle() instanceof AssembledShipEntity ship && ship.isFlyingShip()) {
                        var status = Component.translatable("flight.blockspaceprogram.status", ship.throttle(),
                                String.format(Locale.ROOT, "%.1f", ship.fuel()));
                        int stage = ship.activeStage();
                        status.append("   ").append(stage < 0 ? Component.translatable("flight.blockspaceprogram.stage_waiting")
                                : Component.translatable("flight.blockspaceprogram.stage", stage + 1));
                        int damagedParts = ship.damagedPartCount();
                        if (damagedParts > 0) {
                            status.append("   ").append(Component.translatable("flight.blockspaceprogram.damage", damagedParts)
                                    .withStyle(ChatFormatting.RED));
                        }
                        graphics.drawCenteredString(game.font, status, graphics.guiWidth() / 2, graphics.guiHeight() - 85, 0xFFFFFF);
                        var modes = Component.translatable("flight.blockspaceprogram.modes",
                                Component.translatable("flight.blockspaceprogram.attitude." + ship.attitudeMode().id()),
                                Component.translatable("flight.blockspaceprogram.throttle." + ship.throttleMode().id()));
                        graphics.drawCenteredString(game.font, modes, graphics.guiWidth() / 2, graphics.guiHeight() - 97, 0xFFFFFF);
                    }
                });
    }
}

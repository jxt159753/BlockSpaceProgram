package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinhelpers.camera.new_camera_types.SableCameraTypes;
import dev.ryanhcode.sable.mixinterface.camera.camera_zoom.CameraZoomExtension;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import java.util.function.Predicate;
import net.minecraft.Util;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

public final class SableShipCamera {
    private static final KeyMapping TOGGLE_CAMERA = new KeyMapping("key.blockspaceprogram.toggle_ship_camera",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.blockspaceprogram");
    private static final long REMOUNT_GRACE_MILLIS = 5_000;

    private static CameraType lastShipView = SableCameraTypes.SUB_LEVEL_VIEW;
    private static CameraType shortcutTarget;
    private static CameraType flightView;
    private static long remountDeadline;

    private SableShipCamera() {}

    static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE_CAMERA);
        NeoForge.EVENT_BUS.addListener(SableShipCamera::beforeFrame);
        NeoForge.EVENT_BUS.addListener(SableShipCamera::keyPressed);
        NeoForge.EVENT_BUS.addListener(SableShipCamera::mousePressed);
    }

    public static boolean cyclePerspective(Minecraft game, boolean perspectiveClick) {
        if (perspectiveClick) {
            flightView = null;
            return true;
        }

        CameraType current = game.options.getCameraType();
        // Continue within Minecraft's perspective loop so Sable runs its hooks for every step.
        if (shortcutTarget != null && shortcutTarget != current) {
            return true;
        }
        shortcutTarget = null;
        while (TOGGLE_CAMERA.consumeClick()) {
            flightView = null;
            if (game.player != null && game.player.getVehicle() instanceof AssembledShipEntity ship
                    && ship.getFirstPassenger() == game.player && cameraReady(game, ship)) {
                if (isShipView(current)) {
                    lastShipView = current;
                    shortcutTarget = CameraType.FIRST_PERSON;
                } else {
                    shortcutTarget = lastShipView;
                }
                return true;
            }
        }

        if (flightView != current && isShipView(current) && game.player != null
                && game.player.getVehicle() instanceof AssembledShipEntity ship && cameraReady(game, ship)) {
            rememberView(current, Util.getMillis());
        }
        return false;
    }

    private static void beforeFrame(RenderFrameEvent.Pre event) {
        var game = Minecraft.getInstance();
        var player = game.player;
        if (player == null || !player.isAlive() || player.isSpectator()) {
            flightView = null;
            return;
        }

        var vehicle = player.getVehicle();
        if (vehicle != null && !(vehicle instanceof AssembledShipEntity)) {
            flightView = null;
            return;
        }

        long now = Util.getMillis();
        if (!(vehicle instanceof AssembledShipEntity ship) || !cameraReady(game, ship)) {
            // The new plot and its passenger packet can arrive separately. Allow level loading to finish first.
            if (flightView != null && (vehicle != null || game.screen instanceof ReceivingLevelScreen)) {
                remountDeadline = now + REMOUNT_GRACE_MILLIS;
            } else if (now > remountDeadline) {
                flightView = null;
            }
            return;
        }

        CameraType current = game.options.getCameraType();
        if (current == CameraType.FIRST_PERSON && flightView != null && now <= remountDeadline) {
            // Sable drops the view when the old vehicle disappears. Its zoom lives on the same Camera across levels.
            current = flightView;
            setPerspective(game, current);
        }
        if (isShipView(current)) {
            rememberView(current, now);
        } else {
            flightView = null;
        }
    }

    private static void rememberView(CameraType view, long now) {
        lastShipView = view;
        flightView = view;
        remountDeadline = now + REMOUNT_GRACE_MILLIS;
    }

    private static void keyPressed(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_PRESS) {
            onPress(mapping -> mapping.matches(event.getKey(), event.getScanCode()));
        }
    }

    private static void mousePressed(InputEvent.MouseButton.Post event) {
        if (event.getAction() == GLFW.GLFW_PRESS) {
            onPress(mapping -> mapping.matchesMouse(event.getButton()));
        }
    }

    private static void onPress(Predicate<KeyMapping> matches) {
        var game = Minecraft.getInstance();
        boolean loading = game.screen instanceof ReceivingLevelScreen;
        if (game.getOverlay() != null || game.screen != null && !loading) {
            return;
        }
        if ((flightView != null || usesShipView(game)) && (matches.test(game.options.keyShift)
                || loading && (matches.test(game.options.keyTogglePerspective) || matches.test(TOGGLE_CAMERA)))) {
            flightView = null;
            var zoom = (CameraZoomExtension) game.gameRenderer.getMainCamera();
            zoom.sable$setZoomAmount(0);
            if (isShipView(game.options.getCameraType())) {
                setPerspective(game, CameraType.FIRST_PERSON);
            }
        }
    }

    private static boolean cameraReady(Minecraft game, AssembledShipEntity ship) {
        return ship.isFlyingShip() && game.getCameraEntity() == game.player
                && Sable.HELPER.getVehicleSubLevel(game.player) != null;
    }

    public static boolean usesShipView(Minecraft game) {
        return isShipView(game.options.getCameraType()) && game.player != null
                && game.player.getVehicle() instanceof AssembledShipEntity ship && ship.isFlyingShip();
    }

    public static float clampZoom(float amount) {
        return Math.max(-0.75F, amount);
    }

    private static boolean isShipView(CameraType view) {
        return view == SableCameraTypes.SUB_LEVEL_VIEW || view == SableCameraTypes.SUB_LEVEL_VIEW_UNLOCKED;
    }

    private static void setPerspective(Minecraft game, CameraType view) {
        game.options.setCameraType(view);
        game.gameRenderer.checkEntityPostEffect(view.isFirstPerson() ? game.getCameraEntity() : null);
        game.levelRenderer.needsUpdate();
    }
}

package io.github.jxt159753.blockspaceprogram.client;

import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.client.mixin.CameraEyeHeightAccessor;
import io.github.jxt159753.blockspaceprogram.orbit.BenchmarkReport;
import io.github.jxt159753.blockspaceprogram.orbit.BenchmarkReport.Trial;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class BenchmarkClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int UNLIMITED_FPS = 260;
    private static View view;
    private static Sample sample;

    private BenchmarkClient() {}

    public static void view(int entityId, float yaw, float pitch) {
        var minecraft = Minecraft.getInstance();
        if (entityId < 0) {
            if (view != null) {
                minecraft.options.setCameraType(view.previousCameraType());
                view = null;
            }
            sample = null;
            return;
        }
        var previous = view == null ? minecraft.options.getCameraType() : view.previousCameraType();
        view = new View(entityId, yaw, pitch, previous);
        pinCamera(minecraft);
        if (minecraft.options.enableVsync().get() || minecraft.options.framerateLimit().get() < UNLIMITED_FPS) {
            message(BenchmarkReport.text("limited_fps"));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeFrame(RenderFrameEvent.Pre event) {
        if (view != null) {
            pinCamera(Minecraft.getInstance());
        }
    }

    private static void pinCamera(Minecraft minecraft) {
        var entity = minecraft.level == null ? null : minecraft.level.getEntity(view.entityId());
        if (entity != null) {
            if (minecraft.getCameraEntity() != entity) {
                minecraft.setCameraEntity(entity);
            }
            if (!minecraft.options.getCameraType().isFirstPerson()) {
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void fixCamera(ViewportEvent.ComputeCameraAngles event) {
        var camera = event.getCamera();
        if (view != null && camera.getEntity().getId() == view.entityId()) {
            // The marker's origin is the camera position. Discard the previous target's interpolated eye height.
            var height = (CameraEyeHeightAccessor) camera;
            height.blockspaceprogram$setEyeHeight(0);
            height.blockspaceprogram$setOldEyeHeight(0);
            event.setYaw(view.yaw());
            event.setPitch(view.pitch());
            event.setRoll(0);
        }
    }

    public static void start(Trial trial) {
        var settings = settings(Minecraft.getInstance());
        sample = new Sample(trial, settings, System.nanoTime());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterFrame(RenderFrameEvent.Post event) {
        if (sample == null) {
            return;
        }
        long now = System.nanoTime();
        if (sample.lastFrame != 0) {
            sample.times.add((now - sample.lastFrame) / 1_000_000.0);
        }
        sample.lastFrame = now;
    }

    public static void stop(String id, boolean complete) {
        if (sample == null || !sample.trial.id().equals(id)) {
            return;
        }
        double seconds = (System.nanoTime() - sample.started) / 1_000_000_000.0;
        var finished = sample;
        sample = null;
        if (complete) {
            try {
                message(BenchmarkReport.write(finished.trial, true, finished.times, seconds, finished.settings));
            } catch (IOException error) {
                LOGGER.warn("Could not write client benchmark {}", id, error);
                message(BenchmarkReport.text("write_failed", BenchmarkReport.text("client")));
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        view(-1, 0, 0);
    }

    private static JsonObject settings(Minecraft minecraft) {
        var options = minecraft.options;
        var window = minecraft.getWindow();
        var result = new JsonObject();
        result.addProperty("gl_renderer", GL11.glGetString(GL11.GL_RENDERER));
        result.addProperty("gl_version", GL11.glGetString(GL11.GL_VERSION));
        result.addProperty("window_width", window.getWidth());
        result.addProperty("window_height", window.getHeight());
        result.addProperty("graphics", options.graphicsMode().get().name().toLowerCase(Locale.ROOT));
        result.addProperty("fov", options.fov().get());
        result.addProperty("render_distance", options.renderDistance().get());
        result.addProperty("effective_render_distance", options.getEffectiveRenderDistance());
        result.addProperty("simulation_distance", options.simulationDistance().get());
        result.addProperty("clouds", options.cloudStatus().get().name().toLowerCase(Locale.ROOT));
        result.addProperty("entity_shadows", options.entityShadows().get());
        boolean vsync = options.enableVsync().get();
        int cap = options.framerateLimit().get();
        result.addProperty("vsync", vsync);
        result.addProperty("fps_limit", cap < UNLIMITED_FPS ? cap : null);
        result.addProperty("frame_rate_limited", vsync || cap < UNLIMITED_FPS);
        return result;
    }

    private static void message(Component text) {
        Minecraft.getInstance().gui.getChat().addMessage(text);
    }

    private record View(int entityId, float yaw, float pitch, CameraType previousCameraType) {}

    private static final class Sample {
        private final Trial trial;
        private final JsonObject settings;
        private final List<Double> times = new ArrayList<>();
        private final long started;
        private long lastFrame;

        private Sample(Trial trial, JsonObject settings, long started) {
            this.trial = trial;
            this.settings = settings;
            this.started = started;
        }
    }
}

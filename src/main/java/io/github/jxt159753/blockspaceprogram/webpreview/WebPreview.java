package io.github.jxt159753.blockspaceprogram.webpreview;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.ShipServerConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID)
public final class WebPreview {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static WebPreview running;

    private final MinecraftServer server;
    private final WebPreviewSnapshots snapshots;
    private final WebPreviewHttpServer http;

    private WebPreview(MinecraftServer server, WebPreviewSnapshots snapshots, WebPreviewHttpServer http) {
        this.server = server;
        this.snapshots = snapshots;
        this.http = http;
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        WebPreviewVisibility.register(event);
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) {
        if (!ShipServerConfig.WEB_PREVIEW_ENABLED.get()) {
            return;
        }
        if (ModuleLayer.boot().findModule("jdk.httpserver").isEmpty()) {
            LOGGER.error("Cannot start the web orbit preview: the Java runtime lacks jdk.httpserver");
            return;
        }
        var address = new InetSocketAddress(ShipServerConfig.WEB_PREVIEW_ADDRESS.get().trim(),
                ShipServerConfig.WEB_PREVIEW_PORT.get());
        var server = event.getServer();
        try {
            var snapshots = new WebPreviewSnapshots(server, ShipServerConfig.WEB_PREVIEW_VISIBILITY.get());
            var http = new WebPreviewHttpServer(address, ShipServerConfig.WEB_PREVIEW_MAX_CONNECTIONS.get(), snapshots.feed());
            running = new WebPreview(server, snapshots, http);
            LOGGER.info("Web orbit preview listening on {}", address);
        } catch (IOException error) {
            LOGGER.error("Could not start the web orbit preview on {}", address, error);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        // OrbitalNetworking has committed the clock, burns, and handoffs at normal priority.
        if (running != null && running.server == event.getServer()) {
            running.snapshots.tick();
        }
    }

    @SubscribeEvent
    public static void stop(ServerStoppingEvent event) {
        if (running != null && running.server == event.getServer()) {
            running.http.close();
            running = null;
        }
    }

    static void visibilityChanged(MinecraftServer server, UUID ship) {
        if (running != null && running.server == server) {
            running.snapshots.visibilityChanged(ship);
        }
    }
}

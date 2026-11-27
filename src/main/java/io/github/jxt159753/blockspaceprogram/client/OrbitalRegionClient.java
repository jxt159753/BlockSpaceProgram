package io.github.jxt159753.blockspaceprogram.client;

import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalNetworking.RegionContacts;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalRegion;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSavedData;
import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class OrbitalRegionClient {
    private static final double MODEL_RANGE = 4_000;
    private static final int COLOR = 0xFFA6E8FF;
    private static final Set<UUID> RENDERED_SHIPS = new HashSet<>();
    private static ClientLevel contactsLevel;
    private static RegionContacts contacts;
    private static List<Marker> markers = List.of();

    private OrbitalRegionClient() {}

    public static void receiveContacts(RegionContacts snapshot) {
        contactsLevel = Minecraft.getInstance().level;
        contacts = snapshot;
    }

    static void shipRendered(UUID id) {
        RENDERED_SHIPS.add(id);
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        contactsLevel = null;
        contacts = null;
        markers = List.of();
        RENDERED_SHIPS.clear();
    }

    @SubscribeEvent
    public static void renderContacts(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        try {
            drawContacts(event, Minecraft.getInstance());
        } finally {
            RENDERED_SHIPS.clear();
        }
    }

    private static void drawContacts(RenderLevelStageEvent event, Minecraft game) {
        var level = game.level;
        var view = level == null ? null : PlanetSkyClient.orbitalViewIn(level);
        if (view == null || game.player == null || !ClientOrbitalState.hasClock()) {
            markers = List.of();
            return;
        }
        double now = ClientOrbitalState.orbitSeconds(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        var system = OrbitalSystem.M3;
        Vec3 origin = system.absoluteState(view.reference().orElseThrow(), now).position();
        Vec3 observer = origin.add(OrbitalRegion.toInertial(event.getCamera().getPosition().subtract(view.regionOrigin())));
        var positions = new HashMap<UUID, Vec3>();
        ClientOrbitalState.ships().forEach((id, ship) -> positions.put(id, system.absoluteState(ship.orbit(), now).position()));
        if (contactsLevel == level && contacts != null) {
            double sampleTime = contacts.orbitTicks() / (double) OrbitalSavedData.TICKS_PER_SECOND;
            double elapsed = Math.clamp(now - sampleTime, 0.0, 0.5);
            contacts.ships().forEach(contact -> positions.put(contact.id(),
                    contact.state().position().add(contact.state().velocity().scale(elapsed))));
        }

        var projection = new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        var next = new ArrayList<Marker>();
        UUID ownShip = game.player.getRootVehicle().getUUID();
        for (var entry : positions.entrySet()) {
            if (RENDERED_SHIPS.contains(entry.getKey()) || ownShip.equals(entry.getKey())) {
                continue;
            }
            Vec3 delta = OrbitalRegion.toLocal(entry.getValue().subtract(observer));
            double distance = delta.length();
            if (distance < 1) {
                continue;
            }
            var clip = projection.transform(new Vector4f((float) delta.x, (float) delta.y, (float) delta.z, 1));
            if (clip.w <= 0 || Math.abs(clip.x) >= clip.w || Math.abs(clip.y) >= clip.w) {
                continue;
            }
            next.add(new Marker(clip.x / clip.w, clip.y / clip.w, Math.round(distance)));
            if (distance <= MODEL_RANGE) {
                drawModel(event, game, delta, distance);
            }
        }
        markers = next;
        game.renderBuffers().bufferSource().endBatch(RenderType.lines());
    }

    private static void drawModel(RenderLevelStageEvent event, Minecraft game, Vec3 delta, double distance) {
        // Keep the outline inside the far plane while preserving its direction and apparent size.
        double scale = Math.min(1, game.options.getEffectiveRenderDistance() * 12.0 / distance);
        float size = (float) (Math.max(2, distance * 0.022) * scale);
        var poses = event.getPoseStack();
        var lines = game.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        poses.pushPose();
        poses.translate(delta.x * scale, delta.y * scale, delta.z * scale);
        poses.scale(size, size, size);
        LevelRenderer.renderLineBox(poses, lines, -0.23, -0.5, -0.2, 0.23, 0.5, 0.2, 0.65F, 0.91F, 1, 1);
        LevelRenderer.renderLineBox(poses, lines, -0.75, -0.24, -0.08, 0.75, 0.02, 0.08, 0.65F, 0.91F, 1, 1);
        poses.popPose();
    }

    @SubscribeEvent
    public static void renderHud(RenderGuiEvent.Post event) {
        var game = Minecraft.getInstance();
        if (game.options.hideGui || game.level == null || PlanetSkyClient.orbitalViewIn(game.level) == null) {
            return;
        }
        var gui = event.getGuiGraphics();
        for (var marker : markers) {
            int x = Math.round((marker.x() + 1) * gui.guiWidth() / 2);
            int y = Math.round((1 - marker.y()) * gui.guiHeight() / 2);
            gui.fill(x - 2, y - 1, x + 2, y + 1, COLOR);
            var label = Component.translatable("orbit.blockspaceprogram.contact_distance", marker.distance());
            int halfWidth = Math.min(game.font.width(label) / 2 + 2, gui.guiWidth() / 2);
            int labelX = Math.clamp(x, halfWidth, gui.guiWidth() - halfWidth);
            int labelY = y > gui.guiHeight() - 48
                    ? Math.min(y - game.font.lineHeight - 4, gui.guiHeight() - 39) : y + 5;
            gui.drawCenteredString(game.font, label, labelX, labelY, COLOR);
        }
    }

    private record Marker(float x, float y, long distance) {}
}

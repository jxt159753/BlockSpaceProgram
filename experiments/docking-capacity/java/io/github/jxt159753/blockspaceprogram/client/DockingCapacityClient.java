package io.github.jxt159753.blockspaceprogram.client;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.AssembledShipEntity;
import io.github.jxt159753.blockspaceprogram.assembly.DockingCapacityDesigns;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID, value = Dist.CLIENT)
public final class DockingCapacityClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<UUID> SHOWN = new HashSet<>();
    private static final Set<UUID> RECEIVED = new HashSet<>();
    private static String screenshot;
    private static AssembledShipEntity watched;
    private static long inspectAt;
    private static boolean renderedParts;

    private DockingCapacityClient() {}

    static void partsRendered(AssembledShipEntity ship) {
        if (!DockingCapacityDesigns.isTrialShip(ship) || SHOWN.contains(ship.getUUID())
                || ship.getName().getString().contains("_ops_")) {
            return;
        }
        var design = ship.design();
        if (ship == watched) {
            renderedParts = true;
        }
        if (ModList.get().isLoaded("sable") && ship.isFlyingShip() && design.hasHull() && !SableHull.ready(ship)) {
            return;
        }
        SHOWN.add(ship.getUUID());
        var name = ship.getName().getString();
        LOGGER.info("CAPACITY SHOWN name={} uuid={} nodes={} epoch_ms={}", name, ship.getUUID(),
                design.nodes().size(), System.currentTimeMillis());
        if (name.endsWith("_watch")) {
            screenshot = name + ".png";
        }
    }

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        var game = Minecraft.getInstance();
        var level = game.level;
        if (level == null) {
            return;
        }
        for (var entity : level.entitiesForRendering()) {
            if (entity instanceof AssembledShipEntity ship && DockingCapacityDesigns.isTrialShip(ship)
                    && ship.getName().getString().endsWith("_watch") && ship.design() != null
                    && RECEIVED.add(ship.getUUID())) {
                LOGGER.info("CAPACITY RECEIVED name={} uuid={} nodes={} epoch_ms={}", ship.getName().getString(),
                        ship.getUUID(), ship.design().nodes().size(), System.currentTimeMillis());
                watched = ship;
                inspectAt = System.nanoTime() + 5_000_000_000L;
                renderedParts = false;
            }
        }
    }

    @SubscribeEvent
    public static void endFrame(RenderFrameEvent.Post event) {
        if (watched != null && System.nanoTime() >= inspectAt) {
            if (!watched.isRemoved() && !SHOWN.contains(watched.getUUID())) {
                LOGGER.info("CAPACITY FRAME name={} baked_parts_drawn={} {}", watched.getName().getString(), renderedParts,
                        ModList.get().isLoaded("sable") ? SableHull.status(watched) : "sable=false");
                screenshot = watched.getName().getString() + "-frame.png";
            }
            watched = null;
        }
        if (screenshot != null) {
            var game = Minecraft.getInstance();
            Screenshot.grab(game.gameDirectory, screenshot, game.getMainRenderTarget(),
                    message -> LOGGER.info("{}", message.getString()));
            screenshot = null;
        }
    }

    // Keep Sable types out of the event subscriber's reflected methods.
    private static final class SableHull {
        private static UUID pendingShip;
        private static Set<SectionPos> hullSections = Set.of();

        static boolean ready(AssembledShipEntity ship) {
            var origin = ship.blockPosition();
            if (!(Sable.HELPER.getContaining(ship.level(), origin) instanceof ClientSubLevel body)
                    || !body.isFinalized() || body.getRenderData() == null) {
                return false;
            }
            if (!ship.getUUID().equals(pendingShip)) {
                pendingShip = ship.getUUID();
                hullSections = new HashSet<>();
                for (var node : ship.design().nodes()) {
                    if (node.hull() != null) {
                        var bounds = node.bounds().move(origin.getX(), origin.getY(), origin.getZ());
                        var min = SectionPos.of(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ));
                        var max = SectionPos.of(BlockPos.containing(bounds.maxX - 1, bounds.maxY - 1, bounds.maxZ - 1));
                        SectionPos.betweenClosedStream(min.x(), min.y(), min.z(), max.x(), max.y(), max.z())
                                .forEach(hullSections::add);
                    }
                }
            }
            return hullSections.stream().allMatch(section ->
                    body.getPlot().getChunkHolder(body.getPlot().toLocal(new ChunkPos(section.x(), section.z()))) != null
                            && body.getRenderData().isSectionCompiled(section.x(), section.y(), section.z()));
        }

        static String status(AssembledShipEntity ship) {
            if (!(Sable.HELPER.getContaining(ship.level(), ship.blockPosition()) instanceof ClientSubLevel body)) {
                return "body=missing";
            }
            var renderData = body.getRenderData();
            return "body=present finalized=" + body.isFinalized() + " render_sections="
                    + (renderData == null ? 0 : renderData.getVisibleSectionCount())
                    + " loaded_chunks=" + body.getPlot().getLoadedChunks().size();
        }
    }
}

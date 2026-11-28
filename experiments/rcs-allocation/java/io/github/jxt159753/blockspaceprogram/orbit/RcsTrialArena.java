package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.RcsServerTrial;
import java.io.IOException;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID)
public final class RcsTrialArena {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean VERIFY = Boolean.getBoolean("bsp.rcsAllocation.verify");
    private static RcsServerTrial trial;
    private static long started;
    private static boolean finished;

    private RcsTrialArena() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeTick(ServerTickEvent.Pre event) {
        if (trial != null) {
            started = System.nanoTime();
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterTick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (!VERIFY || finished || !server.isDedicatedServer() || !server.isReady()) {
            return;
        }
        try {
            if (trial == null) {
                if (!ModList.get().isLoaded("sable")) {
                    RcsServerTrial.verifyStatic(server.overworld());
                    finished = true;
                } else if (server.getPlayerCount() != 0) {
                    var observer = server.getPlayerList().getPlayers().getFirst();
                    var space = server.getLevel(BlockSpaceProgram.ORBITAL_SPACE);
                    var origin = OrbitalRegion.get(server).openRcsTrial(observer, space);
                    LOGGER.info("RCS START dimension={} observer={} cc={}", space.dimension().location(), observer.getGameProfile().getName(),
                            ModList.get().isLoaded("computercraft"));
                    trial = new RcsServerTrial(space, origin, observer.getUUID());
                }
            } else if (trial.tick(System.nanoTime() - started)) {
                LOGGER.info("RCS DONE dedicated-server measurements");
                finished = true;
            }
        } catch (RuntimeException | IOException error) {
            if (trial != null) {
                trial.close();
            }
            LOGGER.error("RCS FAIL", error);
            finished = true;
        }
        if (finished) {
            server.halt(false);
        }
    }
}

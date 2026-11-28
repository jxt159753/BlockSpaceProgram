package io.github.jxt159753.blockspaceprogram.assembly;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import java.util.ArrayList;
import java.util.List;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID)
public final class DockingCapacityTiming {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<Completed> COMPLETED = new ArrayList<>();
    private static Runnable queued;
    private static long started;

    private DockingCapacityTiming() {}

    public static void nextTick(Runnable operation) {
        if (queued != null) {
            throw new IllegalStateException("The previous operation is still queued");
        }
        queued = operation;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void beforeTick(ServerTickEvent.Pre event) {
        started = System.nanoTime();
        if (queued != null) {
            var operation = queued;
            queued = null;
            try {
                operation.run();
            } catch (RuntimeException error) {
                LOGGER.warn("CAPACITY operation failed", error);
            }
        }
    }

    public static void success(String operation, AssembledShipEntity ship) {
        if (DockingCapacityDesigns.isTrialShip(ship)) {
            COMPLETED.add(new Completed(operation, ship));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterTick(ServerTickEvent.Post event) {
        if (COMPLETED.isEmpty()) {
            return;
        }
        double tickMs = (System.nanoTime() - started) / 1_000_000.0;
        for (var completed : COMPLETED) {
            var ship = completed.ship();
            LOGGER.info("CAPACITY TICK op={} name={} tick={} ms={} parts={} nodes={} stage={} origins={} damage_index={}",
                    completed.operation(), ship.getName().getString(), event.getServer().getTickCount(), tickMs,
                    ship.design().partCount(), ship.design().nodes().size(), ship.activeStage(),
                    DockingCapacityDesigns.originCount(ship), DockingCapacityDesigns.highestDamageIndex(ship));
        }
        COMPLETED.clear();
    }

    private record Completed(String operation, AssembledShipEntity ship) {}
}

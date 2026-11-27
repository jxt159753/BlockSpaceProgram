package io.github.jxt159753.blockspaceprogram.experiment;

import com.mojang.logging.LogUtils;
import io.github.jxt159753.blockspaceprogram.BlockSpaceProgram;
import io.github.jxt159753.blockspaceprogram.assembly.PlanetFlightProperties;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.slf4j.Logger;

@EventBusSubscriber(modid = BlockSpaceProgram.MOD_ID)
public final class CandidateDimensions {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NAMESPACE = "bsp_candidate_dimensions";
    private static final String SAVE_ID = "bsp_candidate_dimensions_experiment";
    private static final long WARMUP = TimeUnit.SECONDS.toNanos(30);
    private static final long SAMPLE_TIME = TimeUnit.MINUTES.toNanos(5);
    private static final List<Long> TICK_TIMES = new ArrayList<>(6000);
    private static List<ServerLevel> candidates = List.of();
    private static long sampleStart;
    private static boolean sampling;

    private CandidateDimensions() {}

    @SubscribeEvent
    public static void onStarted(ServerStartedEvent event) {
        var server = event.getServer();
        var levels = new ArrayList<ServerLevel>();
        server.getAllLevels().forEach(level -> {
            if (level.dimension().location().getNamespace().equals(NAMESPACE)) {
                levels.add(level);
            }
        });
        candidates = List.copyOf(levels);
        var registered = candidates.stream().map(level -> level.dimension().location()).sorted().toList();
        var eligible = registered.stream().filter(id -> PlanetFlightProperties.profile(id) != null).toList();
        long seed = server.overworld().getSeed();
        int count = Integer.getInteger("bsp.candidateDimensions.satelliteCount", 3);
        var factory = new SavedData.Factory<>(() -> EnabledList.generate(eligible, seed, count), EnabledList::load);
        var saved = server.overworld().getDataStorage().computeIfAbsent(factory, SAVE_ID);
        sampling = Boolean.getBoolean("bsp.candidateDimensions.benchmark");
        if (sampling) {
            Configurator.setLevel(MinecraftServer.class.getName(), Level.DEBUG);
            TICK_TIMES.clear();
            sampleStart = System.nanoTime() + WARMUP;
        }
        LOGGER.info("Candidate dimensions ready: startup_ms={} sable={} seed={} requested={} registered={} eligible={} enabled={}",
                ManagementFactory.getRuntimeMXBean().getUptime(), ModList.get().isLoaded("sable"),
                seed, count, registered, eligible, saved.dimensions);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (!sampling) {
            return;
        }
        long elapsed = System.nanoTime() - sampleStart;
        if (elapsed < 0) {
            return;
        }
        var server = event.getServer();
        if (elapsed < SAMPLE_TIME) {
            // Vanilla has already recorded the cost of this tick without the wait for the next tick.
            var tickTimes = server.getTickTimesNanos();
            TICK_TIMES.add(tickTimes[server.getTickCount() % tickTimes.length]);
            return;
        }

        sampling = false;
        TICK_TIMES.sort(null);
        int size = TICK_TIMES.size();
        double median = (TICK_TIMES.get((size - 1) / 2) + TICK_TIMES.get(size / 2)) / 2_000_000.0;
        double p95 = TICK_TIMES.get((int) Math.ceil(size * 0.95) - 1) / 1_000_000.0;
        // Collect once after sampling so garbage awaiting collection does not skew the heap comparison.
        System.gc();
        long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        int loadedChunks = candidates.stream().mapToInt(level -> level.getChunkSource().getLoadedChunksCount()).sum();
        LOGGER.info("Candidate dimensions sample: count={} ticks={} seconds={} median_ms={} p95_ms={} heap_bytes={} loaded_chunks={}",
                candidates.size(), size, elapsed / 1_000_000_000.0, median, p95, heap, loadedChunks);
        server.halt(false);
    }

    private static final class EnabledList extends SavedData {
        private final List<ResourceLocation> dimensions;

        private EnabledList(List<ResourceLocation> dimensions) {
            this.dimensions = dimensions;
        }

        private static EnabledList generate(List<ResourceLocation> eligible, long seed, int count) {
            var shuffled = new ArrayList<>(eligible);
            Collections.shuffle(shuffled, new Random(seed));
            var data = new EnabledList(List.copyOf(shuffled.subList(0, Math.clamp(count, 0, shuffled.size()))));
            data.setDirty();
            LOGGER.info("Candidate dimensions generated enabled list {}", data.dimensions);
            return data;
        }

        private static EnabledList load(CompoundTag tag, HolderLookup.Provider registries) {
            var dimensions = tag.getList("enabled", Tag.TAG_STRING).stream()
                    .map(value -> ResourceLocation.parse(value.getAsString())).toList();
            return new EnabledList(dimensions);
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            var enabled = new ListTag();
            dimensions.forEach(id -> enabled.add(StringTag.valueOf(id.toString())));
            tag.put("enabled", enabled);
            return tag;
        }
    }
}

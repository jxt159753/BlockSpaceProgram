package io.github.jxt159753.blockspaceprogram.sky.surfacecolor;

import io.github.jxt159753.blockspaceprogram.orbit.OrbitalSystem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

final class SurfaceColorMapBenchmark {
    private static final Logger LOGGER = SurfaceColorMapServer.LOGGER;
    private static final long PHASE_NANOS = 2_000_000_000L;
    private final MinecraftServer server;
    private final Map<ResourceLocation, BiomeGridSampler> samplers;
    private final int y;
    private final Executor workers;
    private volatile TickWindow measuring;

    SurfaceColorMapBenchmark(MinecraftServer server, Map<ResourceLocation, BiomeGridSampler> samplers, int y,
                             Executor workers) {
        this.server = server;
        this.samplers = Map.copyOf(samplers);
        this.y = y;
        this.workers = workers;
    }

    void tick() {
        var window = measuring;
        if (window != null) {
            long[] nanos = server.getTickTimesNanos();
            long duration = nanos[server.getTickCount() % nanos.length];
            if (System.nanoTime() - duration >= window.started) {
                window.ticks.add(duration);
            }
        }
    }

    void run() throws IOException, InterruptedException {
        var idle = new TickWindow("idle");
        measuring = idle;
        try {
            Thread.sleep(PHASE_NANOS / 1_000_000);
        } finally {
            measuring = null;
        }
        server.execute(idle::report);

        var mother = samplers.get(OrbitalSystem.MOTHER_PLANET);
        var moon = samplers.get(OrbitalSystem.MOON);
        var first = measure(mother, 1024);
        measure(mother, 2048);
        measure(moon, SurfaceColorMapServer.MOON_WIDTH);

        for (int height : new int[]{-32, 32, 63, 96, 128, 192, 256}) {
            var sample = mother.sample(1024, height);
            LOGGER.info("Surface map height: grid=1024x512 y={} caves={} cells=524288 palette={}",
                    height, sample.caves(), sample.grid().palette().size());
        }

        // Both workers share the live generator and climate inputs while the server is ticking.
        var other = CompletableFuture.supplyAsync(() -> {
            try {
                return mother.sample(1024, y);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, workers);
        var local = mother.sample(1024, y);
        String concurrentHash = other.join().encoded().hash();
        LOGGER.info("Surface map concurrent: reference={} local={} parallel={} equal={}",
                first, local.encoded().hash(), concurrentHash,
                first.equals(local.encoded().hash()) && first.equals(concurrentHash));
        LOGGER.info("Surface map benchmark finished");
    }

    private String measure(BiomeGridSampler sampler, int width) throws IOException {
        String label = sampler.dimension() + "/" + width;
        var window = new TickWindow(label);
        var samples = new ArrayList<Long>();
        var totals = new ArrayList<Long>();
        int different = 0;
        BiomeGridSampler.Sample first = null;
        measuring = window;
        try {
            do {
                var sample = sampler.sample(width, y);
                if (first == null) {
                    first = sample;
                } else if (!first.encoded().hash().equals(sample.encoded().hash())) {
                    different++;
                }
                samples.add(sample.sampleNanos());
                totals.add(sample.totalNanos());
            } while (samples.size() < 3 || System.nanoTime() - window.started < PHASE_NANOS);
        } finally {
            measuring = null;
        }
        LOGGER.info("Surface map timing: case={} y={} runs={} sample_ms={} total_ms={} median_total_ms={} "
                        + "different={} caves={} raw_bytes={} gzip_bytes={} hash={}",
                label, y, samples.size(), samples.getFirst() / 1e6, totals.getFirst() / 1e6, median(totals),
                different, first.caves(), first.encoded().rawBytes(), first.encoded().compressed().length,
                first.encoded().hash());
        server.execute(window::report);
        return first.encoded().hash();
    }

    private static double median(List<Long> nanos) {
        var sorted = nanos.stream().sorted().toList();
        int size = sorted.size();
        return (sorted.get((size - 1) / 2) + sorted.get(size / 2)) / 2e6;
    }

    private static final class TickWindow {
        private final String label;
        private final long started = System.nanoTime();
        private final List<Long> ticks = new ArrayList<>();

        private TickWindow(String label) {
            this.label = label;
        }

        private void report() {
            ticks.sort(null);
            if (!ticks.isEmpty()) {
                LOGGER.info("Surface map MSPT: case={} ticks={} median_ms={} p95_ms={}", label, ticks.size(),
                        median(ticks), ticks.get((int) Math.ceil(ticks.size() * 0.95) - 1) / 1e6);
            }
        }
    }
}

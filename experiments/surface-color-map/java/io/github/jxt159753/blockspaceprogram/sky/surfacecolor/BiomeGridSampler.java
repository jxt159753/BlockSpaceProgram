package io.github.jxt159753.blockspaceprogram.sky.surfacecolor;

import io.github.jxt159753.blockspaceprogram.orbit.CelestialBody;
import io.github.jxt159753.blockspaceprogram.orbit.PlanetFrame;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

/** Captures the generator's biome inputs on the server thread and samples without loading chunks. */
record BiomeGridSampler(ResourceLocation dimension, BiomeSource source, Climate.Sampler climate, double worldWidth) {
    static BiomeGridSampler capture(ServerLevel level, CelestialBody body) {
        var chunks = level.getChunkSource();
        return new BiomeGridSampler(body.id(), chunks.getGenerator().getBiomeSource(), chunks.randomState().sampler(),
                PlanetFrame.worldWidth(body));
    }

    Sample sample(int width, int y) throws IOException {
        long start = System.nanoTime();
        int height = width / 2;
        int[] sampleX = new int[width];
        for (int x = 0; x < width; x++) {
            sampleX[x] = QuartPos.fromBlock(BiomeGrid.sampleCoordinate(x, width, worldWidth));
        }
        var palette = new LinkedHashMap<ResourceKey<Biome>, Integer>();
        byte[] cells = new byte[width * height];
        int caves = 0;
        int quartY = QuartPos.fromBlock(y);
        for (int row = 0; row < height; row++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Surface map sampling interrupted");
            }
            int quartZ = QuartPos.fromBlock(BiomeGrid.sampleCoordinate(row, height, worldWidth / 2));
            for (int col = 0; col < width; col++) {
                var key = source.getNoiseBiome(sampleX[col], quartY, quartZ, climate).unwrapKey().orElseThrow();
                int index = palette.computeIfAbsent(key, ignored -> palette.size());
                if (index >= BiomeGrid.MAX_PALETTE) {
                    throw new IOException("Surface map has more than " + BiomeGrid.MAX_PALETTE + " biomes");
                }
                cells[row * width + col] = (byte) index;
                if (key.equals(Biomes.LUSH_CAVES) || key.equals(Biomes.DRIPSTONE_CAVES) || key.equals(Biomes.DEEP_DARK)) {
                    caves++;
                }
            }
        }
        var names = palette.keySet().stream().map(key -> key.location().toString()).toList();
        var grid = new BiomeGrid(width, names, cells);
        long sampleNanos = System.nanoTime() - start;
        var encoded = grid.encode();
        return new Sample(grid, encoded, caves, sampleNanos, System.nanoTime() - start);
    }

    record Sample(BiomeGrid grid, BiomeGrid.Encoded encoded, int caves, long sampleNanos, long totalNanos) {}
}

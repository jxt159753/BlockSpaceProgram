package io.github.jxt159753.blockspaceprogram.sky.surfacemap;

import io.github.jxt159753.blockspaceprogram.orbit.CelestialBody;
import io.github.jxt159753.blockspaceprogram.orbit.PlanetFrame;
import java.util.LinkedHashMap;
import java.util.concurrent.CancellationException;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.jetbrains.annotations.Nullable;

/** Captures the biome inputs on the server thread before sampling. */
record SurfaceMapSampler(BiomeSource source, Climate.Sampler climate, double worldWidth, int width, int sampleY) {
    static SurfaceMapSampler capture(ServerLevel level, CelestialBody body, int width, int sampleY) {
        var chunks = level.getChunkSource();
        return new SurfaceMapSampler(chunks.getGenerator().getBiomeSource(), chunks.randomState().sampler(),
                PlanetFrame.worldWidth(body), width, sampleY);
    }

    @Nullable
    BiomeGrid sample() {
        int height = width / 2;
        int quartY = QuartPos.fromBlock(sampleY);
        int[] sampleX = new int[width];
        for (int column = 0; column < width; column++) {
            sampleX[column] = quartCoordinate(column, width, worldWidth);
        }
        var palette = new LinkedHashMap<ResourceKey<Biome>, Integer>();
        var cells = new byte[width * height];
        for (int row = 0; row < height; row++) {
            checkInterrupted();
            int quartZ = quartCoordinate(row, height, worldWidth / 2);
            for (int column = 0; column < width; column++) {
                var key = source.getNoiseBiome(sampleX[column], quartY, quartZ, climate).unwrapKey().orElseThrow();
                int index = palette.computeIfAbsent(key, ignored -> palette.size());
                if (index >= BiomeGrid.MAX_PALETTE) {
                    return null;
                }
                cells[row * width + column] = (byte) index;
            }
        }
        var names = palette.keySet().stream().map(key -> key.location().toString()).toList();
        return new BiomeGrid(width, sampleY, names, cells);
    }

    boolean matches(BiomeGrid grid) {
        int quartY = QuartPos.fromBlock(sampleY);
        return grid.matchesSamples((column, row) -> {
            checkInterrupted();
            return source.getNoiseBiome(quartCoordinate(column, width, worldWidth), quartY,
                    quartCoordinate(row, width / 2, worldWidth / 2), climate)
                    .unwrapKey().orElseThrow().location().toString();
        });
    }

    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Surface map sampling interrupted");
        }
    }

    private static int quartCoordinate(int cell, int cellCount, double span) {
        return QuartPos.fromBlock(BiomeGrid.sampleCoordinate(cell, cellCount, span));
    }
}

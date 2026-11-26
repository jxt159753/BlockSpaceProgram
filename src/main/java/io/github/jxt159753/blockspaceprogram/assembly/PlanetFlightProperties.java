package io.github.jxt159753.blockspaceprogram.assembly;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/** Local flight parameters, keyed by dimension ID in the ship_flight data pack directory. */
record PlanetFlightProperties(double seaLevel, double radius, double surfaceGravity, double seaLevelDensity,
                              double atmosphereTop, double scaleHeight) {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Codec<Double> NONNEGATIVE = Codec.doubleRange(0, Double.MAX_VALUE);
    private static final Codec<Double> POSITIVE = Codec.doubleRange(1, Double.MAX_VALUE);
    private static final Codec<PlanetFlightProperties> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.doubleRange(-Double.MAX_VALUE, Double.MAX_VALUE).fieldOf("sea_level").forGetter(PlanetFlightProperties::seaLevel),
            POSITIVE.fieldOf("radius").forGetter(PlanetFlightProperties::radius),
            NONNEGATIVE.fieldOf("surface_gravity").forGetter(PlanetFlightProperties::surfaceGravity),
            NONNEGATIVE.fieldOf("sea_level_density").forGetter(PlanetFlightProperties::seaLevelDensity),
            NONNEGATIVE.fieldOf("atmosphere_top").forGetter(PlanetFlightProperties::atmosphereTop),
            POSITIVE.fieldOf("scale_height").forGetter(PlanetFlightProperties::scaleHeight)
    ).apply(instance, PlanetFlightProperties::new));
    private static volatile Map<ResourceLocation, PlanetFlightProperties> planets = Map.of();

    static void register(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "ship_flight") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> json, ResourceManager resources, ProfilerFiller profiler) {
                var loaded = new HashMap<ResourceLocation, PlanetFlightProperties>();
                json.forEach((dimension, entry) -> CODEC.parse(JsonOps.INSTANCE, entry)
                        .resultOrPartial(error -> LOGGER.error("Invalid ship flight parameters for {}: {}", dimension, error))
                        .ifPresent(properties -> loaded.put(dimension, properties)));
                planets = Map.copyOf(loaded);
            }
        });
    }

    @Nullable
    static PlanetFlightProperties in(Level level) {
        return planets.get(level.dimension().location());
    }

    double gravityAt(double y) {
        double ratio = radius / Math.max(1, radius + (y - seaLevel));
        return surfaceGravity * ratio * ratio;
    }

    double relativeDensityAt(double y) {
        double altitude = Math.max(0, y - seaLevel);
        if (seaLevelDensity == 0 || altitude >= atmosphereTop) {
            return 0;
        }
        return Math.exp(-altitude / scaleHeight) * Math.expm1((altitude - atmosphereTop) / scaleHeight)
                / Math.expm1(-atmosphereTop / scaleHeight);
    }
}

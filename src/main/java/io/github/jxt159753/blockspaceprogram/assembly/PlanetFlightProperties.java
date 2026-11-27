package io.github.jxt159753.blockspaceprogram.assembly;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/** Local flight parameters, keyed by dimension ID in the ship_flight data pack directory. */
public record PlanetFlightProperties(double seaLevel, double radius, double surfaceGravity, double seaLevelDensity,
                                     double atmosphereTop, double scaleHeight) {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Codec<Double> NONNEGATIVE = Codec.doubleRange(0, Double.MAX_VALUE);
    private static final Codec<Double> POSITIVE = Codec.doubleRange(1, Double.MAX_VALUE);
    private static final MapCodec<PlanetFlightProperties> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.doubleRange(-Double.MAX_VALUE, Double.MAX_VALUE).fieldOf("sea_level").forGetter(PlanetFlightProperties::seaLevel),
            POSITIVE.fieldOf("radius").forGetter(PlanetFlightProperties::radius),
            NONNEGATIVE.fieldOf("surface_gravity").forGetter(PlanetFlightProperties::surfaceGravity),
            NONNEGATIVE.fieldOf("sea_level_density").forGetter(PlanetFlightProperties::seaLevelDensity),
            NONNEGATIVE.fieldOf("atmosphere_top").forGetter(PlanetFlightProperties::atmosphereTop),
            POSITIVE.fieldOf("scale_height").forGetter(PlanetFlightProperties::scaleHeight)
    ).apply(instance, PlanetFlightProperties::new));
    public static final StreamCodec<FriendlyByteBuf, PlanetFlightProperties> STREAM_CODEC = StreamCodec.of((buf, planet) -> {
        buf.writeDouble(planet.seaLevel());
        buf.writeDouble(planet.radius());
        buf.writeDouble(planet.surfaceGravity());
        buf.writeDouble(planet.seaLevelDensity());
        buf.writeDouble(planet.atmosphereTop());
        buf.writeDouble(planet.scaleHeight());
    }, buf -> new PlanetFlightProperties(buf.readDouble(), buf.readDouble(), buf.readDouble(),
            buf.readDouble(), buf.readDouble(), buf.readDouble()));
    private static volatile Map<ResourceLocation, Profile> planets = Map.of();

    static void register(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "ship_flight") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> json, ResourceManager resources, ProfilerFiller profiler) {
                var loaded = new HashMap<ResourceLocation, Profile>();
                json.forEach((dimension, entry) -> Profile.CODEC.parse(JsonOps.INSTANCE, entry)
                        .resultOrPartial(error -> LOGGER.error("Invalid ship flight parameters for {}: {}", dimension, error))
                        .ifPresent(properties -> loaded.put(dimension, properties)));
                planets = Map.copyOf(loaded);
            }
        });
    }

    @Nullable
    static PlanetFlightProperties in(Level level) {
        var profile = profile(level.dimension().location());
        return profile == null ? null : profile.flight();
    }

    @Nullable
    public static Profile profile(ResourceLocation dimension) {
        return planets.get(dimension);
    }

    public static Map<ResourceLocation, PlanetFlightProperties> all() {
        var flightProperties = new HashMap<ResourceLocation, PlanetFlightProperties>();
        planets.forEach((id, profile) -> flightProperties.put(id, profile.flight()));
        return Map.copyOf(flightProperties);
    }

    public boolean hasAtmosphere() {
        return seaLevelDensity > 0 && atmosphereTop > 0;
    }

    double gravityAt(double y) {
        double ratio = radius / Math.max(1, radius + (y - seaLevel));
        return surfaceGravity * ratio * ratio;
    }

    public double relativeDensityAt(double y) {
        double altitude = Math.max(0, y - seaLevel);
        if (!hasAtmosphere() || altitude >= atmosphereTop) {
            return 0;
        }
        return Math.exp(-altitude / scaleHeight) * Math.expm1((altitude - atmosphereTop) / scaleHeight)
                / Math.expm1(-atmosphereTop / scaleHeight);
    }

    public record Profile(PlanetFlightProperties flight, Optional<HandoffHeights> handoff) {
        static final Codec<Profile> CODEC = RecordCodecBuilder.<Profile>create(instance -> instance.group(
                PlanetFlightProperties.CODEC.forGetter(Profile::flight),
                HandoffHeights.CODEC.optionalFieldOf("orbit_handoff").forGetter(Profile::handoff)
        ).apply(instance, Profile::new)).validate(profile -> {
            var heights = profile.handoff().orElse(null);
            if (heights != null && (heights.ascent() <= heights.descent()
                    || (profile.flight().hasAtmosphere() && heights.descent() <= profile.flight().atmosphereTop()))) {
                return DataResult.error(() -> "Expected ascent_altitude > descent_altitude above any atmosphere",
                        new Profile(profile.flight(), Optional.empty()));
            }
            return DataResult.success(profile);
        });
    }

    public record HandoffHeights(double ascent, double descent) {
        static final Codec<HandoffHeights> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                NONNEGATIVE.fieldOf("ascent_altitude").forGetter(HandoffHeights::ascent),
                NONNEGATIVE.fieldOf("descent_altitude").forGetter(HandoffHeights::descent)
        ).apply(instance, HandoffHeights::new));
    }
}

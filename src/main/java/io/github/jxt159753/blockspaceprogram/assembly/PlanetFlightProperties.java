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
import java.util.List;
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
    private static final Codec<Double> FINITE = Codec.doubleRange(-Double.MAX_VALUE, Double.MAX_VALUE);
    private static final Codec<Double> NONNEGATIVE = Codec.doubleRange(0, Double.MAX_VALUE);
    private static final Codec<Double> POSITIVE = Codec.doubleRange(1, Double.MAX_VALUE);
    private static final Codec<Float> UNIT = Codec.floatRange(0, 1);
    private static final Codec<Float> STRENGTH = Codec.floatRange(0, Float.MAX_VALUE);
    private static final MapCodec<PlanetFlightProperties> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            FINITE.fieldOf("sea_level").forGetter(PlanetFlightProperties::seaLevel),
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
    private static Map<ResourceLocation, PlanetFlightProperties> clientPlanets = Map.of();

    static void register(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(new Gson(), "ship_flight") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> json, ResourceManager resources, ProfilerFiller profiler) {
                var loaded = new HashMap<ResourceLocation, Profile>();
                json.forEach((dimension, entry) -> Profile.parse(dimension, entry)
                        .resultOrPartial(error -> LOGGER.error("Invalid ship flight parameters for {}: {}", dimension, error))
                        .ifPresent(properties -> loaded.put(dimension, properties)));
                planets = Map.copyOf(loaded);
            }
        });
    }

    @Nullable
    static PlanetFlightProperties in(Level level) {
        var dimension = level.dimension().location();
        if (level.isClientSide()) {
            return clientIn(dimension);
        }
        var profile = profile(dimension);
        return profile == null ? null : profile.flight();
    }

    public static void setClientProperties(Map<ResourceLocation, PlanetFlightProperties> properties) {
        clientPlanets = Map.copyOf(properties);
    }

    @Nullable
    public static PlanetFlightProperties clientIn(ResourceLocation dimension) {
        return clientPlanets.get(dimension);
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

    public static Map<ResourceLocation, Appearance> appearances() {
        var appearances = new HashMap<ResourceLocation, Appearance>();
        planets.forEach((id, profile) -> appearances.put(id, profile.appearance()));
        return Map.copyOf(appearances);
    }

    public static Appearance appearance(ResourceLocation dimension) {
        var profile = profile(dimension);
        return profile == null ? Appearance.DEFAULT : profile.appearance();
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

    public record Profile(PlanetFlightProperties flight, Optional<HandoffHeights> handoff, Appearance appearance,
                          double reentryHeatMultiplier) {
        private static final Codec<Appearance> APPEARANCE_CODEC =
                Appearance.CODEC.optionalFieldOf("appearance", Appearance.DEFAULT).codec();
        private static final Codec<Profile> FLIGHT_CODEC = RecordCodecBuilder.<Profile>create(instance -> instance.group(
                PlanetFlightProperties.CODEC.forGetter(Profile::flight),
                HandoffHeights.CODEC.optionalFieldOf("orbit_handoff").forGetter(Profile::handoff),
                NONNEGATIVE.optionalFieldOf("reentry_heat_multiplier", ReentryTemperature.DEFAULT_MULTIPLIER)
                        .forGetter(Profile::reentryHeatMultiplier)
        ).apply(instance, (flight, handoff, multiplier) ->
                new Profile(flight, handoff, Appearance.DEFAULT, multiplier))).validate(profile -> {
            var heights = profile.handoff().orElse(null);
            if (heights != null && (heights.ascent() <= heights.descent()
                    || (profile.flight().hasAtmosphere() && heights.descent() <= profile.flight().atmosphereTop()))) {
                return DataResult.error(() -> "Expected ascent_altitude > descent_altitude above any atmosphere",
                        new Profile(profile.flight(), Optional.empty(), profile.appearance(), profile.reentryHeatMultiplier()));
            }
            return DataResult.success(profile);
        });

        static DataResult<Profile> parse(ResourceLocation dimension, JsonElement json) {
            var result = APPEARANCE_CODEC.parse(JsonOps.INSTANCE, json);
            result.error().ifPresent(error -> LOGGER.error("Invalid appearance for {}: {}", dimension, error.message()));
            Appearance appearance = result.result().orElse(Appearance.DEFAULT);
            return FLIGHT_CODEC.parse(JsonOps.INSTANCE, json)
                    .map(profile -> new Profile(profile.flight(), profile.handoff(), appearance, profile.reentryHeatMultiplier()));
        }
    }

    public record HandoffHeights(double ascent, double descent) {
        static final Codec<HandoffHeights> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                NONNEGATIVE.fieldOf("ascent_altitude").forGetter(HandoffHeights::ascent),
                NONNEGATIVE.fieldOf("descent_altitude").forGetter(HandoffHeights::descent)
        ).apply(instance, HandoffHeights::new));
    }

    public record Appearance(SurfaceMap surfaceMap, MoonSurface moonSurface, AtmosphereAppearance atmosphere,
                             float cloudOpacity, float waterGlintStrength, float surfaceDetailStrength) {
        public static final Appearance DEFAULT = new Appearance(SurfaceMap.DEFAULT, MoonSurface.DEFAULT,
                AtmosphereAppearance.DEFAULT, 0.90F, 1, 1);

        public static final Codec<Appearance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                SurfaceMap.CODEC.optionalFieldOf("surface_map", DEFAULT.surfaceMap()).forGetter(Appearance::surfaceMap),
                MoonSurface.CODEC.optionalFieldOf("moon_surface", DEFAULT.moonSurface()).forGetter(Appearance::moonSurface),
                AtmosphereAppearance.CODEC.optionalFieldOf("atmosphere", DEFAULT.atmosphere()).forGetter(Appearance::atmosphere),
                UNIT.optionalFieldOf("cloud_opacity", DEFAULT.cloudOpacity()).forGetter(Appearance::cloudOpacity),
                STRENGTH.optionalFieldOf("water_glint_strength", DEFAULT.waterGlintStrength()).forGetter(Appearance::waterGlintStrength),
                STRENGTH.optionalFieldOf("surface_detail_strength", DEFAULT.surfaceDetailStrength()).forGetter(Appearance::surfaceDetailStrength)
        ).apply(instance, Appearance::new));
    }

    public record SurfaceMap(boolean enabled, int sampleY, double blendStart, double blendEnd) {
        static final SurfaceMap DEFAULT = new SurfaceMap(true, 256, 50_000, 200_000);
        static final Codec<SurfaceMap> CODEC = RecordCodecBuilder.<SurfaceMap>create(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("enabled", DEFAULT.enabled()).forGetter(SurfaceMap::enabled),
                Codec.INT.optionalFieldOf("sample_y", DEFAULT.sampleY()).forGetter(SurfaceMap::sampleY),
                FINITE.optionalFieldOf("blend_start", DEFAULT.blendStart()).forGetter(SurfaceMap::blendStart),
                FINITE.optionalFieldOf("blend_end", DEFAULT.blendEnd()).forGetter(SurfaceMap::blendEnd)
        ).apply(instance, SurfaceMap::new)).validate(map -> map.blendStart() < map.blendEnd()
                ? DataResult.success(map) : DataResult.error(() -> "Expected blend_start < blend_end"));
    }

    public record MoonSurface(Color highlandColor, Color mareColor) {
        static final MoonSurface DEFAULT = new MoonSurface(new Color(0.97F, 0.96F, 0.944F),
                new Color(0.56F, 0.546F, 0.524F));
        static final Codec<MoonSurface> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Color.CODEC.optionalFieldOf("highland_color", DEFAULT.highlandColor()).forGetter(MoonSurface::highlandColor),
                Color.CODEC.optionalFieldOf("mare_color", DEFAULT.mareColor()).forGetter(MoonSurface::mareColor)
        ).apply(instance, MoonSurface::new));
    }

    public record AtmosphereAppearance(Color extinction, float halo, Color limbDayColor, Color limbTwilightColor) {
        static final AtmosphereAppearance DEFAULT = new AtmosphereAppearance(new Color(0.047F, 0.085F, 0.145F), 0.058F,
                new Color(0.15F, 0.46F, 0.86F), new Color(0.86F, 0.34F, 0.11F));
        static final Codec<AtmosphereAppearance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Color.CODEC.optionalFieldOf("extinction", DEFAULT.extinction()).forGetter(AtmosphereAppearance::extinction),
                STRENGTH.optionalFieldOf("halo", DEFAULT.halo()).forGetter(AtmosphereAppearance::halo),
                Color.CODEC.optionalFieldOf("limb_day_color", DEFAULT.limbDayColor()).forGetter(AtmosphereAppearance::limbDayColor),
                Color.CODEC.optionalFieldOf("limb_twilight_color", DEFAULT.limbTwilightColor()).forGetter(AtmosphereAppearance::limbTwilightColor)
        ).apply(instance, AtmosphereAppearance::new));
    }

    public record Color(float red, float green, float blue) {
        static final Codec<Color> CODEC = UNIT.listOf(3, 3).xmap(
                values -> new Color(values.get(0), values.get(1), values.get(2)),
                color -> List.of(color.red(), color.green(), color.blue()));
    }
}

package io.github.jxt159753.blockspaceprogram.sky;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public record SurfaceColor(int rgb, boolean water, int priority, boolean fromBiomeId) {
    private static final Codec<Integer> RGB_CODEC = Codec.STRING.comapFlatMap(
            value -> value.matches("#[0-9A-Fa-f]{6}")
                    ? DataResult.success(Integer.parseInt(value.substring(1), 16))
                    : DataResult.error(() -> "Expected a surface color in #RRGGBB format: " + value),
            value -> "#%06X".formatted(value));

    public static final Codec<SurfaceColor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RGB_CODEC.fieldOf("color").forGetter(SurfaceColor::rgb),
            Codec.BOOL.optionalFieldOf("water", false).forGetter(SurfaceColor::water),
            Codec.INT.optionalFieldOf("priority", 0).forGetter(SurfaceColor::priority)
    ).apply(instance, SurfaceColor::new));

    public SurfaceColor(int rgb, boolean water, int priority) {
        this(rgb, water, priority, false);
    }

    public static SurfaceColor merge(SurfaceColor earlier, boolean earlierIsBiomeId,
                                     SurfaceColor later, boolean laterIsBiomeId) {
        // NeoForge stores the latest selector, so the winning ID match must survive in the value.
        if (laterIsBiomeId) {
            return new SurfaceColor(later.rgb, later.water, later.priority, true);
        }
        if (earlier.fromBiomeId || earlierIsBiomeId) {
            return new SurfaceColor(earlier.rgb, earlier.water, earlier.priority, true);
        }
        return earlier.priority > later.priority ? earlier : later;
    }
}

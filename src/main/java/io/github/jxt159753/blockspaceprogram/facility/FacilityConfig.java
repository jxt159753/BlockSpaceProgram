package io.github.jxt159753.blockspaceprogram.facility;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class FacilityConfig {
    private final ModConfigSpec.ConfigValue<List<? extends Integer>> bakingTableMaxBlocks;
    private final ModConfigSpec.ConfigValue<List<? extends Integer>> assemblyTableMaxParts;
    private final ModConfigSpec.ConfigValue<List<? extends Number>> launchPadMaxMass;
    private final ModConfigSpec.IntValue defaultTier;

    public FacilityConfig(ModConfigSpec.Builder builder, int defaultMaxBlocks) {
        builder.push("facility");
        bakingTableMaxBlocks = builder.comment("Maximum blocks per baked part at each baking table tier. 0 means unlimited.")
                .defineList("bakingTableMaxBlocks", List.of(defaultMaxBlocks), () -> 0,
                        value -> value instanceof Integer count && count >= 0);
        assemblyTableMaxParts = builder.comment("Maximum parts per ship at each assembly table tier. 0 means unlimited.")
                .defineList("assemblyTableMaxParts", List.of(10, 36), () -> 0,
                        value -> value instanceof Integer count && count >= 0);
        launchPadMaxMass = builder.comment("Maximum fully fueled mass in kg at each launch pad tier. 0 means unlimited.")
                .<Number>defineList("launchPadMaxMass", List.of(0), () -> 0,
                        value -> value instanceof Number mass && Double.isFinite(mass.doubleValue()) && mass.doubleValue() >= 0);
        defaultTier = builder.comment("Tier of a facility without a saved tier. The first tier is 1.")
                .defineInRange("defaultTier", 1, 1, Integer.MAX_VALUE);
        builder.pop();
    }

    int defaultTier() {
        return defaultTier.get();
    }

    List<? extends Number> limits(Facility facility) {
        return switch (facility) {
            case BAKING_TABLE -> bakingTableMaxBlocks.get();
            case ASSEMBLY_TABLE -> assemblyTableMaxParts.get();
            case LAUNCH_PAD -> launchPadMaxMass.get();
        };
    }
}

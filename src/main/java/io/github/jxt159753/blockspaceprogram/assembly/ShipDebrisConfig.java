package io.github.jxt159753.blockspaceprogram.assembly;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ShipDebrisConfig {
    public static final ModConfigSpec SPEC;
    static final ModConfigSpec.IntValue MAX_DEBRIS;
    static final ModConfigSpec.IntValue DISTANCE;
    static final ModConfigSpec.IntValue LIFETIME;

    static {
        var builder = new ModConfigSpec.Builder();
        builder.push("debris");
        MAX_DEBRIS = builder.comment("Maximum debris bodies per dimension. Oldest debris is removed first.")
                .defineInRange("maxCount", 32, 0, Integer.MAX_VALUE);
        DISTANCE = builder.comment("When players are in the dimension, remove debris farther than this many blocks from them.",
                        "0 disables this rule.")
                .defineInRange("distance", 256, 0, Integer.MAX_VALUE);
        LIFETIME = builder.comment("Maximum age of debris in seconds of game time. 0 disables this rule.")
                .defineInRange("lifetimeSeconds", 300, 0, Integer.MAX_VALUE);
        builder.pop();
        SPEC = builder.build();
    }

    private ShipDebrisConfig() {}
}

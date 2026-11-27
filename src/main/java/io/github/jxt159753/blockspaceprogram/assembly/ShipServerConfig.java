package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.facility.FacilityConfig;
import io.github.jxt159753.blockspaceprogram.part.BakedPart;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ShipServerConfig {
    public static final ModConfigSpec SPEC;
    public static final FacilityConfig FACILITY;
    static final ModConfigSpec.IntValue MAX_DEBRIS;
    static final ModConfigSpec.IntValue DISTANCE;
    static final ModConfigSpec.IntValue LIFETIME;
    static final ModConfigSpec.IntValue MAX_REMOTE_SHIPS;
    static final ModConfigSpec.IntValue MAX_HULL_BLOCKS;
    static final ModConfigSpec.DoubleValue DOCK_DISTANCE;
    static final ModConfigSpec.DoubleValue DOCK_ANGLE;
    static final ModConfigSpec.DoubleValue DOCK_SPEED;
    private static final ModConfigSpec.DoubleValue ORBIT_ENTRY_DISTANCE;
    private static final ModConfigSpec.DoubleValue ORBIT_EXIT_DISTANCE;
    private static final ModConfigSpec.DoubleValue ORBIT_ENTRY_SPEED;
    private static final ModConfigSpec.ConfigValue<String> STAR_SYSTEM_SPACING;
    private static final ModConfigSpec.ConfigValue<String> STAR_SYSTEM_SHAPE;
    private static final ModConfigSpec.ConfigValue<String> STAR_SYSTEM_SEED;

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
        builder.push("flight");
        MAX_REMOTE_SHIPS = builder.comment("Maximum ships kept loaded by a remote flight controller per dimension.")
                .defineInRange("maxRemoteShips", 8, 0, Integer.MAX_VALUE);
        builder.pop();
        builder.push("hull");
        MAX_HULL_BLOCKS = builder.comment("Maximum blocks in the connected hull collected by a ship assembler.")
                .defineInRange("maxBlocks", 8192, 1, Integer.MAX_VALUE);
        builder.pop();
        builder.push("docking");
        DOCK_DISTANCE = builder.comment("Maximum distance between docking port centers in metres.")
                .defineInRange("captureDistance", 0.25, 0.01, 2.0);
        DOCK_ANGLE = builder.comment("Maximum angular error from a square docking alignment in degrees.")
                .defineInRange("captureAngle", 8.0, 0.1, 45.0);
        DOCK_SPEED = builder.comment("Maximum relative speed of the docking port centers in metres per second.")
                .defineInRange("captureSpeed", 0.5, 0.01, 5.0);
        builder.pop();
        builder.push("orbitalPhysics");
        ORBIT_ENTRY_DISTANCE = builder.comment("Distance in metres at which a nearby ship can enter local physics.")
                .defineInRange("entryDistance", 1_000.0, 1.0, 4_000.0);
        ORBIT_EXIT_DISTANCE = builder.comment("Distance in metres at which an isolated uncrewed ship leaves local physics.",
                        "The effective distance is at least one metre greater than entryDistance.")
                .defineInRange("exitDistance", 1_200.0, 2.0, 4_001.0);
        ORBIT_ENTRY_SPEED = builder.comment("Maximum relative speed in metres per second for an uncrewed ship to enter local physics.")
                .defineInRange("maxEntrySpeed", 40.0, 0.0, 1_000.0);
        builder.pop();
        FACILITY = new FacilityConfig(builder, BakedPart.MAX_BLOCKS);
        builder.comment("Star system settings only apply when creating a world.").push("star_system");
        STAR_SYSTEM_SPACING = builder.comment("The Moon's orbital spacing: near, medium, or far.")
                .define("orbit_spacing", "medium", value -> "near".equals(value) || "medium".equals(value) || "far".equals(value));
        STAR_SYSTEM_SHAPE = builder.comment("The Moon's orbit shape: regular or natural.")
                .define("orbit_shape", "regular", value -> "regular".equals(value) || "natural".equals(value));
        STAR_SYSTEM_SEED = builder.comment("A signed 64-bit decimal seed. Leave blank to derive it from the world seed.")
                .define("seed", "", ShipServerConfig::validStarSystemSeed);
        builder.pop();
        SPEC = builder.build();
    }

    private ShipServerConfig() {}

    public static double orbitEntryDistance() {
        return ORBIT_ENTRY_DISTANCE.get();
    }

    public static double orbitExitDistance() {
        return Math.max(orbitEntryDistance() + 1, ORBIT_EXIT_DISTANCE.get());
    }

    public static double orbitEntrySpeed() {
        return ORBIT_ENTRY_SPEED.get();
    }

    public static String starSystemSpacing() {
        return STAR_SYSTEM_SPACING.get();
    }

    public static String starSystemShape() {
        return STAR_SYSTEM_SHAPE.get();
    }

    public static String starSystemSeed() {
        return STAR_SYSTEM_SEED.get().trim();
    }

    private static boolean validStarSystemSeed(Object value) {
        if (!(value instanceof String input)) {
            return false;
        }
        String seed = input.trim();
        if (seed.isEmpty()) {
            return true;
        }
        try {
            Long.parseLong(seed);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}

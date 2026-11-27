package io.github.jxt159753.blockspaceprogram.assembly;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class ShipServerConfig {
    public static final ModConfigSpec SPEC;
    static final ModConfigSpec.IntValue MAX_DEBRIS;
    static final ModConfigSpec.IntValue DISTANCE;
    static final ModConfigSpec.IntValue LIFETIME;
    static final ModConfigSpec.IntValue MAX_REMOTE_SHIPS;
    static final ModConfigSpec.DoubleValue DOCK_DISTANCE;
    static final ModConfigSpec.DoubleValue DOCK_ANGLE;
    static final ModConfigSpec.DoubleValue DOCK_SPEED;
    private static final ModConfigSpec.DoubleValue ORBIT_ENTRY_DISTANCE;
    private static final ModConfigSpec.DoubleValue ORBIT_EXIT_DISTANCE;
    private static final ModConfigSpec.DoubleValue ORBIT_ENTRY_SPEED;

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
}

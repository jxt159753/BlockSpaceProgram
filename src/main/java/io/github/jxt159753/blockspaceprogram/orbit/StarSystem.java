package io.github.jxt159753.blockspaceprogram.orbit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.jxt159753.blockspaceprogram.assembly.ShipServerConfig;
import java.util.Locale;
import java.util.SplittableRandom;

/** Immutable world-generation settings and the resulting satellite conic. */
record StarSystem(Spacing spacing, Shape shape, long seed, OrbitElements moonOrbit) {
    private static final double TAU = 2 * Math.PI;
    // FNV-1a of the UTF-8 identifier blockspaceprogram:star_system.
    private static final long WORLD_SEED_SALT = 0xcfde9169ddc0b182L;
    static final StarSystem LEGACY = new StarSystem(Spacing.MEDIUM, Shape.REGULAR, 0, OrbitalSystem.LEGACY_MOON_ORBIT);
    static final Codec<StarSystem> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.xmap(Spacing::parse, Spacing::id).fieldOf("orbit_spacing").forGetter(StarSystem::spacing),
            Codec.STRING.xmap(Shape::parse, Shape::id).fieldOf("orbit_shape").forGetter(StarSystem::shape),
            Codec.LONG.fieldOf("seed").forGetter(StarSystem::seed),
            OrbitElements.CODEC.fieldOf("moon_orbit").forGetter(StarSystem::moonOrbit)
    ).apply(instance, StarSystem::new));

    static long seedFromWorld(long seed) {
        return seed ^ WORLD_SEED_SALT;
    }

    static StarSystem forNewWorld(long worldSeed) {
        String override = ShipServerConfig.starSystemSeed();
        return generate(Spacing.parse(ShipServerConfig.starSystemSpacing()), Shape.parse(ShipServerConfig.starSystemShape()),
                override.isEmpty() ? seedFromWorld(worldSeed) : Long.parseLong(override));
    }

    static StarSystem generate(Spacing spacing, Shape shape, long seed) {
        var random = new SplittableRandom(seed);
        boolean natural = shape == Shape.NATURAL;
        for (int attempt = 1; ; attempt++) {
            double axis = random.nextDouble(spacing.minAxis, spacing.maxAxis);
            double phase = random.nextDouble(TAU);
            var elements = natural
                    ? OrbitElements.fromSemiMajorAxis(axis, random.nextDouble(0.05), random.nextDouble(Math.toRadians(5)),
                            random.nextDouble(TAU), random.nextDouble(TAU), phase, 0)
                    : OrbitElements.fromSemiMajorAxis(axis, 0, 0, 0, 0, phase, 0);
            try {
                OrbitalSystem.withMoonOrbit(elements);
                return new StarSystem(spacing, shape, seed, elements);
            } catch (IllegalArgumentException exception) {
                if (attempt == 100) {
                    throw exception;
                }
            }
        }
    }

    double hohmannSeconds(OrbitalSystem system) {
        var mother = system.body(OrbitalSystem.MOTHER_PLANET);
        double transferAxis = (mother.radius() + 10_000 + moonOrbit.semiMajorAxis()) / 2;
        return OrbitalPlanner.hohmannTransferSeconds(transferAxis, mother.gravitationalParameter());
    }

    enum Spacing {
        NEAR(220_000, 260_000), MEDIUM(330_000, 390_000), FAR(480_000, 600_000);

        final double minAxis;
        final double maxAxis;

        Spacing(double minAxis, double maxAxis) {
            this.minAxis = minAxis;
            this.maxAxis = maxAxis;
        }

        static Spacing parse(String id) {
            return valueOf(id.toUpperCase(Locale.ROOT));
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    enum Shape {
        REGULAR, NATURAL;

        static Shape parse(String id) {
            return valueOf(id.toUpperCase(Locale.ROOT));
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}

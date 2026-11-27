package io.github.jxt159753.blockspaceprogram.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class PlanetStarField {
    static final long SEED = 10842L;
    static final int STAR_COUNT = 7500;
    private static final int[] MAGNITUDE_WEIGHTS = {50, 28, 14, 6, 2};
    private static final float[] BRIGHTNESS = {0.18F, 0.28F, 0.44F, 0.66F, 1.0F};
    private static final float[] HALF_WIDTH = {0.115F, 0.140F, 0.163F, 0.194F, 0.226F};

    private PlanetStarField() {}

    static List<Star> generate(long seed) {
        var random = new Random(seed);
        var stars = new ArrayList<Star>(STAR_COUNT);
        for (int i = 0; i < STAR_COUNT; i++) {
            double z = 2 * random.nextDouble() - 1;
            double azimuth = 2 * Math.PI * random.nextDouble();
            double equatorialRadius = Math.sqrt(1 - z * z);
            double roll = 2 * Math.PI * random.nextDouble();
            int magnitude = magnitude(random.nextInt(100));
            float warmth = 2 * random.nextFloat() - 1;
            stars.add(new Star(equatorialRadius * Math.cos(azimuth), equatorialRadius * Math.sin(azimuth), z,
                    roll, HALF_WIDTH[magnitude], BRIGHTNESS[magnitude], warmth));
        }
        return List.copyOf(stars);
    }

    private static int magnitude(int draw) {
        int tier = 0;
        while (draw >= MAGNITUDE_WEIGHTS[tier]) {
            draw -= MAGNITUDE_WEIGHTS[tier++];
        }
        return tier;
    }

    record Star(double x, double y, double z, double roll, float halfWidth, float brightness, float warmth) {}
}

package io.github.jxt159753.blockspaceprogram.client;

final class SurfaceMapBlend {
    private SurfaceMapBlend() {}

    static float proceduralWeight(double altitude, double start, double end) {
        if (altitude <= start) {
            return 0;
        }
        if (altitude >= end) {
            return 1;
        }
        double fraction = (altitude - start) / (end - start);
        return (float) (fraction * fraction * (3 - 2 * fraction));
    }
}

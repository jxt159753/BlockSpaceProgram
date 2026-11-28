package io.github.jxt159753.blockspaceprogram.assembly;

final class ReentryTemperature {
    static final double AMBIENT_KELVIN = 293.15;
    static final double DEFAULT_MULTIPLIER = 2;
    static final double DEFAULT_RESISTANCE = 650;
    private static final double AIR_SPECIFIC_HEAT = 1_005;
    private static final double HEAT_TRANSFER = 3;
    private static final double COOLING_RATE = 1.0 / 8;

    private ReentryTemperature() {}

    static double equilibrium(double speed, double multiplier) {
        return AMBIENT_KELVIN + multiplier * (speed * speed / (2 * AIR_SPECIFIC_HEAT));
    }

    static double step(double current, double equilibrium, double massFlow, double mass, double seconds) {
        if (massFlow <= 0) {
            return approach(current, AMBIENT_KELVIN, COOLING_RATE * seconds);
        }
        return approach(current, equilibrium, HEAT_TRANSFER * massFlow / Math.max(0.01, mass) * seconds);
    }

    private static double approach(double current, double target, double exponent) {
        return current + (target - current) * -Math.expm1(-exponent);
    }
}

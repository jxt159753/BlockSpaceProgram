package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.part.ParachutePerformance;

/** State of one canopy. Progress is linear in drag area within each opening phase. */
public record ParachuteDeployment(Phase phase, double progress, boolean recovered) {
    static final ParachuteDeployment STOWED = new ParachuteDeployment(Phase.STOWED, 0, false);
    static final ParachuteDeployment RECOVERED = new ParachuteDeployment(Phase.STOWED, 0, true);
    private static final ParachuteDeployment ARMED = new ParachuteDeployment(Phase.ARMED, 0, false);
    private static final int MAX_PACKED_PROGRESS = 63;

    public enum Phase {
        STOWED("stowed"), ARMED("armed"), SEMI_OPEN("semi_open"), FULL_OPEN("full_open");

        private static final Phase[] ALL = values();
        private final String id;

        Phase(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    ParachuteDeployment advance(ParachutePerformance performance, boolean armed, boolean disabled,
            double relativeDensity, double airspeed, double height, double seconds) {
        if (recovered) {
            return this;
        }
        if (disabled || !armed) {
            return STOWED;
        }
        double increment = seconds / performance.openingTime();
        if (phase == Phase.STOWED || phase == Phase.ARMED) {
            return relativeDensity > 0 && relativeDensity >= performance.minRelativeDensity()
                    && airspeed <= performance.maxDeploySpeed() ? opening(Phase.SEMI_OPEN, increment) : ARMED;
        }
        if (readyForFull() && relativeDensity > 0 && height <= performance.fullDeployHeight()) {
            return opening(Phase.FULL_OPEN, increment);
        }
        return progress == 1 ? this : opening(phase, progress + increment);
    }

    private static ParachuteDeployment opening(Phase phase, double progress) {
        return new ParachuteDeployment(phase, Math.min(1, progress), false);
    }

    boolean readyForFull() {
        return phase == Phase.SEMI_OPEN && progress == 1;
    }

    double dragArea(ParachutePerformance performance) {
        return area(performance, phase, progress);
    }

    byte pack() {
        return (byte) (phase.ordinal() << 6 | Math.round(progress * MAX_PACKED_PROGRESS));
    }

    public static Phase phase(byte packed) {
        return Phase.ALL[Byte.toUnsignedInt(packed) >> 6];
    }

    public static double dragArea(ParachutePerformance performance, byte packed) {
        return area(performance, phase(packed), (double) (packed & MAX_PACKED_PROGRESS) / MAX_PACKED_PROGRESS);
    }

    private static double area(ParachutePerformance performance, Phase phase, double progress) {
        return switch (phase) {
            case STOWED, ARMED -> 0;
            case SEMI_OPEN -> performance.semiDragArea() * progress;
            case FULL_OPEN -> Math.fma(performance.dragArea() - performance.semiDragArea(), progress, performance.semiDragArea());
        };
    }

    public static double terminalSpeed(double mass, double gravity, double density, double dragArea) {
        return Math.sqrt(2 * mass * gravity / (density * dragArea));
    }
}

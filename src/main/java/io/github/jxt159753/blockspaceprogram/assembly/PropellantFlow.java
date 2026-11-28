package io.github.jxt159753.blockspaceprogram.assembly;

import java.util.Arrays;

/** One full or partial throttle burn with fixed engine rates and shared electrical supply. */
final class PropellantFlow {
    private final double[] fuel;
    private final double[] chemicalFlow;
    private final double[] electricFlow;
    private final double[] power;
    private double electric;
    private double flowRate;
    private double powerDraw;

    PropellantFlow(int groups) {
        fuel = new double[groups];
        chemicalFlow = new double[groups];
        electricFlow = new double[groups];
        power = new double[groups];
    }

    void reset(double electric) {
        this.electric = electric;
        Arrays.fill(fuel, 0);
        Arrays.fill(chemicalFlow, 0);
        Arrays.fill(electricFlow, 0);
        Arrays.fill(power, 0);
    }

    void addFuel(int group, double amount) {
        fuel[group] += amount;
    }

    void addEngine(int group, double flow, double draw) {
        if (draw > 0) {
            electricFlow[group] += flow;
            power[group] += draw;
        } else {
            chemicalFlow[group] += flow;
        }
    }

    double fuel(int group) {
        return fuel[group];
    }

    double electric() {
        return electric;
    }

    double flowRate() {
        return flowRate;
    }

    private double groupFlow(int group) {
        return chemicalFlow[group] + (electric > 0 ? electricFlow[group] : 0);
    }

    boolean feeding(int group) {
        return fuel[group] > 0 && groupFlow(group) > 0;
    }

    double nextSegment(double limit) {
        flowRate = 0;
        powerDraw = 0;
        for (int group = 0; group < fuel.length; group++) {
            double flow = groupFlow(group);
            if (fuel[group] > 0 && flow > 0) {
                flowRate += flow;
                limit = Math.min(limit, fuel[group] / flow);
                if (electric > 0) {
                    powerDraw += power[group];
                }
            }
        }
        return powerDraw > 0 ? Math.min(limit, electric / powerDraw) : limit;
    }

    /** Settle this segment and report when the last group supplying fuel ran dry. */
    boolean consume(double seconds) {
        boolean fuelDepleted = false;
        boolean stillFeeding = false;
        for (int group = 0; group < fuel.length; group++) {
            if (feeding(group)) {
                fuel[group] = remaining(fuel[group], groupFlow(group), seconds);
                fuelDepleted |= fuel[group] == 0;
                stillFeeding |= fuel[group] > 0;
            }
        }
        electric = remaining(electric, powerDraw, seconds);
        return fuelDepleted && !stillFeeding;
    }

    private static double remaining(double amount, double rate, double seconds) {
        return rate > 0 && seconds >= amount / rate ? 0 : Math.max(0, amount - rate * seconds);
    }
}

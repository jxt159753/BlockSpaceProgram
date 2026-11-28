package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.assembly.AeroProjection.Side;
import org.junit.Test;

import static io.github.jxt159753.blockspaceprogram.assembly.ReentryTemperature.AMBIENT_KELVIN;
import static io.github.jxt159753.blockspaceprogram.assembly.ReentryTemperature.DEFAULT_MULTIPLIER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ReentryTemperatureTest {
    @Test
    public void equilibriumAtMotherAndMoonReturnSpeeds() {
        assertEquals(845.4137, ReentryTemperature.equilibrium(745, DEFAULT_MULTIPLIER), 1e-3);
        assertEquals(1210.1649, ReentryTemperature.equilibrium(960, DEFAULT_MULTIPLIER), 1e-3);
        assertEquals(AMBIENT_KELVIN, ReentryTemperature.equilibrium(960, 0), 0);
    }

    @Test
    public void bottomShieldCoversPodAndCrosswindExposesBoth() {
        var grid = new AeroProjection.Grid();
        grid.addBlock(0, 0, 0, 4, 0, 1);
        grid.addBlock(0, 4, 0, 4, 1, 1);
        var projection = grid.summarize(2);
        assertEquals(1, projection.exposedArea(Side.NEG_Y, 0), 0);
        assertEquals(0, projection.exposedArea(Side.NEG_Y, 1), 0);
        assertEquals(1, projection.exposedArea(Side.POS_X, 0), 0);
        assertEquals(1, projection.exposedArea(Side.POS_X, 1), 0);
    }

    @Test
    public void heatingDependsOnMassAndExposureAndCoolingIsTimeBased() {
        double target = ReentryTemperature.equilibrium(745, DEFAULT_MULTIPLIER);
        double hot = ReentryTemperature.step(AMBIENT_KELVIN, target, 100, 200, 1);
        assertTrue(hot > AMBIENT_KELVIN && hot < target);
        assertTrue(hot > ReentryTemperature.step(AMBIENT_KELVIN, target, 100, 400, 1));
        assertTrue(hot > ReentryTemperature.step(AMBIENT_KELVIN, target, 50, 200, 1));
        assertEquals(AMBIENT_KELVIN, ReentryTemperature.step(AMBIENT_KELVIN, target, 0, 200, 1), 0);
        double halfStep = ReentryTemperature.step(AMBIENT_KELVIN, target, 100, 200, 0.5);
        assertEquals(hot, ReentryTemperature.step(halfStep, target, 100, 200, 0.5), 1e-9);
        assertEquals(AMBIENT_KELVIN + (hot - AMBIENT_KELVIN) / Math.E,
                ReentryTemperature.step(hot, target, 0, 200, 8), 1e-9);
    }
}

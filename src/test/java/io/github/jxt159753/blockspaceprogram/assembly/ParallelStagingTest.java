package io.github.jxt159753.blockspaceprogram.assembly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class ParallelStagingTest {
    @Test
    public void coreBurnsThroughBoosterSeparation() {
        var launch = new VacuumPropulsion(680, 380, 0, List.of(
                chemicalGroup(0, 300, 8_000, 0, 8),
                chemicalGroup(1, 40, 8_000, 0, 8),
                chemicalGroup(2, 40, 8_000, 0, 8)));
        var first = launch.burnStage(new boolean[] {false, true, true});
        assertEquals(1000 * Math.log(680.0 / 560), first.deltaV(), 1e-9);
        assertEquals(5, first.seconds(), 1e-9);
        assertEquals(120, first.fuel(), 1e-9);

        double coreFuel = 300 * first.fuelFraction(0);
        var separated = new VacuumPropulsion(200 + coreFuel, coreFuel, 0,
                List.of(chemicalGroup(0, coreFuel, 8_000, 0, 8)));
        var second = separated.estimate(Double.POSITIVE_INFINITY);
        assertEquals(1000 * Math.log(460.0 / 200), second.deltaV(), 1e-9);
        assertEquals(32.5, second.seconds(), 1e-9);
        assertEquals(260, second.fuel(), 1e-9);
    }

    @Test
    public void opposedGroupsSpendFuelWithoutDeliveringDeltaV() {
        var propulsion = new VacuumPropulsion(300, 80, 0, List.of(
                chemicalGroup(0, 40, 0, 8_000, 8),
                chemicalGroup(1, 40, 0, -8_000, 8)));
        var burn = propulsion.estimate(1);
        assertFalse(burn.supplies(1));
        assertTrue(propulsion.thrustCancels());
        assertEquals(0, propulsion.netThrust(), 0);
        assertEquals(0, burn.deltaV(), 0);
        assertEquals(80, burn.fuel(), 1e-9);
        assertEquals(5, burn.seconds(), 1e-9);
    }

    @Test
    public void sumsSimultaneousVectorsBeforeCalculatingDeltaV() {
        var propulsion = new VacuumPropulsion(100, 11, 0, List.of(
                chemicalGroup(0, 3, 300, 0, 3),
                chemicalGroup(1, 8, 0, 400, 4)));
        var burn = propulsion.estimate(Double.POSITIVE_INFINITY);
        assertEquals(500, propulsion.netThrust(), 1e-9);
        assertEquals(500.0 / 7 * Math.log(100.0 / 93) + 100 * Math.log(93.0 / 89), burn.deltaV(), 1e-9);
        assertEquals(2, burn.seconds(), 1e-9);
    }

    private static VacuumPropulsion.Group chemicalGroup(int index, double fuel, double x, double y, double flow) {
        return new VacuumPropulsion.Group(index, fuel, new VacuumPropulsion.Drive(x, y, 0, flow),
                VacuumPropulsion.Drive.OFF, 0);
    }
}

package io.github.jxt159753.blockspaceprogram.assembly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class ParallelStagingTest {
    @Test
    public void coreBurnsThroughBoosterSeparation() {
        var core = new VacuumPropulsion.Group(300, 8_000, 8);
        var booster = new VacuumPropulsion.Group(40, 8_000, 8);
        var launch = new VacuumPropulsion(680, 380, List.of(core, booster, booster));
        var first = launch.estimate(Double.POSITIVE_INFINITY, 5);
        assertEquals(1000 * Math.log(680.0 / 560), first.deltaV(), 1e-9);
        assertEquals(5, first.seconds(), 1e-9);
        assertEquals(120, first.fuel(), 1e-9);

        double coreFuel = 300 * VacuumPropulsion.fractionRemaining(300, 8, first.seconds());
        var separated = new VacuumPropulsion(200 + coreFuel, coreFuel,
                List.of(new VacuumPropulsion.Group(coreFuel, 8_000, 8)));
        var second = separated.estimate(Double.POSITIVE_INFINITY);
        assertEquals(1000 * Math.log(460.0 / 200), second.deltaV(), 1e-9);
        assertEquals(32.5, second.seconds(), 1e-9);
        assertEquals(260, second.fuel(), 1e-9);
    }

    @Test
    public void opposedGroupsSpendFuelWithoutDeliveringDeltaV() {
        var propulsion = new VacuumPropulsion(300, 80, List.of(
                new VacuumPropulsion.Group(40, 0, 8_000, 0, 8),
                new VacuumPropulsion.Group(40, 0, -8_000, 0, 8)));
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
        var propulsion = new VacuumPropulsion(100, 11, List.of(
                new VacuumPropulsion.Group(3, 300, 0, 0, 3),
                new VacuumPropulsion.Group(8, 0, 400, 0, 4)));
        var burn = propulsion.estimate(Double.POSITIVE_INFINITY);
        assertEquals(500, propulsion.netThrust(), 1e-9);
        assertEquals(500.0 / 7 * Math.log(100.0 / 93) + 100 * Math.log(93.0 / 89), burn.deltaV(), 1e-9);
        assertEquals(2, burn.seconds(), 1e-9);
    }
}

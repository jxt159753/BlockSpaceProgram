package io.github.jxt159753.blockspaceprogram.assembly;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class VacuumPropulsionTest {
    @Test
    public void estimatesAcrossDepletionOfOneFuelGroup() {
        var propulsion = new VacuumPropulsion(1_000, 100, List.of(
                new VacuumPropulsion.Group(10, 2_000, 10),
                new VacuumPropulsion.Group(90, 9_000, 30)));
        double afterFirst = 275 * Math.log(1000.0 / 960);
        double deltaV = afterFirst + 300 * Math.log(960.0 / 930);
        var burn = propulsion.estimate(deltaV);
        assertTrue(burn.supplies(deltaV));
        assertEquals(2, burn.seconds(), 1e-10);
        assertEquals(70, burn.fuel(), 1e-10);
        assertEquals(0, VacuumPropulsion.fractionRemaining(10, 10, burn.seconds()), 0);
        assertEquals(1.0 / 3, VacuumPropulsion.fractionRemaining(90, 30, burn.seconds()), 1e-10);
        assertFalse(propulsion.estimate(200).supplies(200));
    }
}

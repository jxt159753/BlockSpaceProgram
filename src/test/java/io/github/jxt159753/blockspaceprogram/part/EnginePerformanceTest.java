package io.github.jxt159753.blockspaceprogram.part;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.joml.Vector3d;
import org.junit.Test;

public class EnginePerformanceTest {
    @Test
    public void twoChambersEachFeedOneTwoByTwoNozzle() {
        var first = new EnginePerformance(80_000, 330, 200);
        var second = new EnginePerformance(120_000, 390, 180);
        var firstOutlet = squareOutlet(0);
        var secondOutlet = squareOutlet(4);
        assertEquals(new Vector3d(1, 0, 1), firstOutlet);
        assertEquals(new Vector3d(5, 0, 1), secondOutlet);

        var combined = EnginePerformance.combine(List.of(first, second));
        assertEquals(200_000, combined.thrust(), 1e-9);
        assertEquals(363.559322, combined.vacuumSpecificImpulse(), 1e-6);
        assertEquals(187.5, combined.seaLevelSpecificImpulse(), 1e-6);

        var moment = firstOutlet.cross(0, first.thrust(), 0).add(secondOutlet.cross(0, second.thrust(), 0));
        assertEquals(new Vector3d(-200_000, 0, 680_000), moment);
        assertEquals(3.4, moment.z / combined.thrust(), 1e-9);
        assertEquals(1.0, -moment.x / combined.thrust(), 1e-9);
    }

    private static Vector3d squareOutlet(int originX) {
        return EnginePerformance.outletCenter(List.of(
                new Vector3d(originX + 0.5, 0, 0.5),
                new Vector3d(originX + 1.5, 0, 0.5),
                new Vector3d(originX + 0.5, 0, 1.5),
                new Vector3d(originX + 1.5, 0, 1.5)));
    }
}

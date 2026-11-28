package io.github.jxt159753.blockspaceprogram.assembly;

import io.github.jxt159753.blockspaceprogram.assembly.AeroProjection.Grid;
import io.github.jxt159753.blockspaceprogram.assembly.AeroProjection.Side;
import org.joml.Vector3d;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AeroProjectionTest {
    private static final double EPS = 1e-9;

    @Test
    public void stackedCubesOccludeEachOtherAndDistinguishTheFront() {
        var projection = stack(1);
        for (Side side : Side.values()) {
            assertEquals(side.axis == 1 ? 9 : 27, projection.area(side), EPS);
        }
        assertEquals(9, projection.exposedArea(Side.POS_Y, 2), EPS);
        assertEquals(0, projection.exposedArea(Side.POS_Y, 0), EPS);
        assertEquals(9, projection.exposedArea(Side.NEG_Y, 0), EPS);
        assertEquals(9, projection.exposedArea(Side.POS_X, 1), EPS);

        var smoothTop = stack(0.3);
        var up = new Vector3d(0, 1, 0);
        var down = new Vector3d(0, -1, 0);
        assertEquals(9, projection.dragArea(up), EPS);
        assertEquals(2.7, smoothTop.dragArea(up), EPS);
        assertEquals(9, smoothTop.dragArea(down), EPS);
        assertEquals(new Vector3d(1.5, 9, 1.5), smoothTop.centerOfPressure(up, new Vector3d()));
        assertEquals(24.9 * 0.6 + 2.7 * 0.8, smoothTop.dragArea(new Vector3d(0.6, 0.8, 0)), EPS);
    }

    @Test
    public void sideForceTurnsTheBottomTowardTheWind() {
        var grid = new Grid();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    grid.addBlock(4 * x, 4 * y, 4 * z, 4, 0, 1);
                }
            }
        }
        var projection = grid.summarize(1);
        var windward = new Vector3d(1, 0, 0);
        var moment = new Vector3d();
        projection.addMoment(windward, new Vector3d(-100, 0, 0), new Vector3d(1.5, 0.5, 1.5), 0.25, moment);
        assertEquals(new Vector3d(0, 0, 25), moment);
        assertTrue(moment.cross(new Vector3d(0, -1, 0), new Vector3d()).dot(windward) > 0);
    }

    @Test
    public void quarterOffsetAndZeroCoefficientStillOcclude() {
        var grid = new Grid();
        grid.addBlock(-2, 0, -4, 4, 0, 1);
        grid.addBlock(-1, 4, -4, 4, 1, 0);
        var projection = grid.summarize(2);
        var up = new Vector3d(0, 1, 0);
        assertEquals(1.25, projection.area(Side.POS_Y), EPS);
        assertEquals(0.25, projection.exposedArea(Side.POS_Y, 0), EPS);
        assertEquals(1, projection.exposedArea(Side.POS_Y, 1), EPS);
        assertEquals(0.25, projection.dragArea(up), EPS);
    }

    private static AeroProjection stack(double topCoefficient) {
        var grid = new Grid();
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 9; y++) {
                for (int z = 0; z < 3; z++) {
                    grid.addBlock(4 * x, 4 * y, 4 * z, 4, y / 3, y == 8 ? topCoefficient : 1);
                }
            }
        }
        return grid.summarize(3);
    }
}

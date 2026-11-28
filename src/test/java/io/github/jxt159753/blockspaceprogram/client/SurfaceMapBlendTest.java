package io.github.jxt159753.blockspaceprogram.client;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SurfaceMapBlendTest {
    @Test
    public void isZeroBelowStartAndOneAboveEnd() {
        assertEquals(0, SurfaceMapBlend.proceduralWeight(10_000, 50_000, 200_000), 0);
        assertEquals(0, SurfaceMapBlend.proceduralWeight(50_000, 50_000, 200_000), 0);
        assertEquals(1, SurfaceMapBlend.proceduralWeight(200_000, 50_000, 200_000), 0);
        assertEquals(1, SurfaceMapBlend.proceduralWeight(600_000, 50_000, 200_000), 0);
    }

    @Test
    public void usesSmoothstepWithCustomHeights() {
        assertEquals(0.15625, SurfaceMapBlend.proceduralWeight(25_000, 20_000, 40_000), 0);
        assertEquals(0.5, SurfaceMapBlend.proceduralWeight(30_000, 20_000, 40_000), 0);
        assertEquals(0.84375, SurfaceMapBlend.proceduralWeight(35_000, 20_000, 40_000), 0);
    }
}

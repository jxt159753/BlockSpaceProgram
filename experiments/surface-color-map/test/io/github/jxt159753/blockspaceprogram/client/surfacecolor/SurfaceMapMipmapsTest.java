package io.github.jxt159753.blockspaceprogram.client.surfacecolor;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SurfaceMapMipmapsTest {
    @Test
    public void averagesRgbInLinearSpaceAndAlphaAsWaterCoverageThroughOneTexel() {
        var levels = SurfaceMapMipmaps.build(4, 2, new int[] {
                0xff000000, 0xff000000, 0x00ffffff, 0x00ffffff,
                0xff000000, 0xff000000, 0x00ffffff, 0x00ffffff
        });
        assertEquals(3, levels.length);
        assertArrayEquals(new int[] {0xff000000, 0x00ffffff}, levels[1]);
        assertArrayEquals(new int[] {0x80bcbcbc}, levels[2]);
    }
}

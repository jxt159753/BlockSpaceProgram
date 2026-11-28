package io.github.jxt159753.blockspaceprogram.client;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class SurfaceMapMipmapsTest {
    @Test
    public void averagesRgbInLinearSpaceThroughTheFinalRectangularLevel() {
        var pixels = new int[] {
                0xff000000, 0xff000000, 0x00ffffff, 0x00ffffff,
                0xff000000, 0xff000000, 0x00ffffff, 0x00ffffff
        };
        var half = SurfaceMapMipmaps.downsample(4, 2, pixels);
        assertArrayEquals(new int[] {0xff000000, 0x00ffffff}, half);
        assertArrayEquals(new int[] {0x80bcbcbc}, SurfaceMapMipmaps.downsample(2, 1, half));
    }

    @Test
    public void averagesColorChannelsAndWaterCoverageIndependently() {
        var pixels = new int[] {0xff0000ff, 0x0000ffff, 0x7fffffff, 0x00000000};
        assertArrayEquals(new int[] {0x6089bce1}, SurfaceMapMipmaps.downsample(2, 2, pixels));
    }
}

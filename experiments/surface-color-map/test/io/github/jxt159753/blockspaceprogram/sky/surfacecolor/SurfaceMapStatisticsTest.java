package io.github.jxt159753.blockspaceprogram.sky.surfacecolor;

import static io.github.jxt159753.blockspaceprogram.sky.surfacecolor.SurfaceMapStatistics.DEEP_OCEAN;
import static io.github.jxt159753.blockspaceprogram.sky.surfacecolor.SurfaceMapStatistics.LAND;
import static io.github.jxt159753.blockspaceprogram.sky.surfacecolor.SurfaceMapStatistics.OCEAN;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SurfaceMapStatisticsTest {
    @Test
    public void wrapsLongitudeAndJoinsShallowAndDeepOceanAcrossFourNeighbors() {
        byte[] cells = {
                LAND, OCEAN, OCEAN, LAND,
                OCEAN, LAND, DEEP_OCEAN, OCEAN,
                LAND, OCEAN, LAND, OCEAN
        };
        assertEquals(new SurfaceMapStatistics.Summary(5, 6, 1, 2, 6), SurfaceMapStatistics.summarize(cells, 4));
    }
}

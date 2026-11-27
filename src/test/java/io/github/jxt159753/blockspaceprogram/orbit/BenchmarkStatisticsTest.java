package io.github.jxt159753.blockspaceprogram.orbit;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BenchmarkStatisticsTest {
    @Test
    public void oddSampleCount() {
        var result = BenchmarkStatistics.summarize(List.of(4.0, 1.0, 2.0));
        assertEquals(2.0, result.medianMs(), 0.0);
        assertEquals(4.0, result.p95Ms(), 0.0);
    }

    @Test
    public void evenSampleCountUsesNearestRankWithoutInterpolating() {
        var samples = new java.util.ArrayList<Double>();
        samples.add(100.0);
        for (int value = 1; value <= 19; value++) {
            samples.add((double) value);
        }
        var result = BenchmarkStatistics.summarize(samples);
        assertEquals(10.5, result.medianMs(), 0.0);
        assertEquals(19.0, result.p95Ms(), 0.0);
        assertEquals(100.0, samples.getFirst(), 0.0);
    }

    @Test
    public void oneSample() {
        var result = BenchmarkStatistics.summarize(List.of(1.75));
        assertEquals(1.75, result.medianMs(), 0.0);
        assertEquals(1.75, result.p95Ms(), 0.0);
    }
}

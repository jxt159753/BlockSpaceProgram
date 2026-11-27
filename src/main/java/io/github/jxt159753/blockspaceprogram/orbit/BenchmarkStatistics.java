package io.github.jxt159753.blockspaceprogram.orbit;

import java.util.List;

final class BenchmarkStatistics {
    private BenchmarkStatistics() {}

    static Summary summarize(List<Double> samples) {
        var ordered = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int size = ordered.length;
        int middle = size / 2;
        double median = size % 2 == 0 ? (ordered[middle - 1] + ordered[middle]) / 2 : ordered[middle];
        int rank = (int) Math.ceil(0.95 * size);
        return new Summary(median, ordered[rank - 1]);
    }

    record Summary(double medianMs, double p95Ms) {}
}

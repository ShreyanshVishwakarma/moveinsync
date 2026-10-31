package com.moveinsync.config.bench;

import java.util.Arrays;

/**
 * Tiny statistics helper: percentiles from a sorted array, no dependency.
 */
final class BenchStats {

    private BenchStats() {
    }

    static long percentile(long[] sortedValues, double percentile) {
        if (sortedValues.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(percentile / 100.0 * sortedValues.length) - 1;
        index = Math.max(0, Math.min(sortedValues.length - 1, index));
        return sortedValues[index];
    }

    static long[] sortedCopy(long[] values) {
        long[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        return copy;
    }

    static String nanos(long value) {
        if (value < 1_000) {
            return value + " ns";
        }
        if (value < 1_000_000) {
            return String.format("%.2f us", value / 1_000.0);
        }
        return String.format("%.2f ms", value / 1_000_000.0);
    }
}

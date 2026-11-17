package com.tracelens.platform.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LatencyHistogramTest {

    @Test
    void bucketUpperBoundContainsTheValue() {
        for (long micros : new long[]{2, 10, 99, 100, 1_000, 12_345, 500_000, 3_000_000}) {
            int b = LatencyHistogram.bucketFor(micros);
            assertThat(LatencyHistogram.upperBoundMicros(b)).isGreaterThanOrEqualTo(micros);
            if (b > 0) assertThat(LatencyHistogram.upperBoundMicros(b - 1)).isLessThan(micros);
        }
    }

    @Test
    void percentilesAreWithinBucketError() {
        long[] counts = new long[LatencyHistogram.BUCKETS];
        for (int ms = 1; ms <= 1000; ms++) counts[LatencyHistogram.bucketFor(ms * 1000L)]++;
        long p50 = LatencyHistogram.percentile(counts, 0.50);
        long p99 = LatencyHistogram.percentile(counts, 0.99);
        assertThat(p50).isBetween(500_000L, 600_000L);
        assertThat(p99).isBetween(990_000L, 1_190_000L);
    }

    @Test
    void emptyHistogramIsZero() {
        assertThat(LatencyHistogram.percentile(new long[LatencyHistogram.BUCKETS], 0.95)).isEqualTo(0L);
    }

    @Test
    void hugeValuesAreClamped() {
        assertThat(LatencyHistogram.bucketFor(Long.MAX_VALUE)).isEqualTo(LatencyHistogram.BUCKETS - 1);
        assertThat(LatencyHistogram.bucketFor(0)).isEqualTo(0);
    }
}

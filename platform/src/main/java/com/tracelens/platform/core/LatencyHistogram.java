package com.tracelens.platform.core;

/**
 * Fixed log-scale latency buckets (growth factor 1.2, 100 buckets: 1µs .. ~83s).
 * <p>
 * Buckets are mergeable by simple addition, which is what lets us store per-minute
 * histograms in PostgreSQL and compute p50/p95/p99 over any time window with a SUM.
 * Relative error of a percentile estimate is bounded by the bucket width (≤ 20%).
 */
public final class LatencyHistogram {
    public static final int BUCKETS = 100;
    private static final double GROWTH = 1.2;
    private static final double LOG_GROWTH = Math.log(GROWTH);

    private LatencyHistogram() {}

    public static int bucketFor(long micros) {
        if (micros <= 1) return 0;
        int b = (int) Math.ceil(Math.log(micros) / LOG_GROWTH) - 1;
        b = Math.max(0, Math.min(BUCKETS - 1, b));
        // align with the integer upper bounds (matters for tiny values where buckets round together)
        while (b > 0 && upperBoundMicros(b - 1) >= micros) b--;
        while (b < BUCKETS - 1 && upperBoundMicros(b) < micros) b++;
        return b;
    }

    /** Inclusive upper bound of a bucket, in microseconds. */
    public static long upperBoundMicros(int bucket) {
        return (long) Math.ceil(Math.pow(GROWTH, bucket + 1));
    }

    /** Percentile (0..1) estimate from bucket counts. Returns 0 for an empty histogram. */
    public static long percentile(long[] counts, double q) {
        long total = 0;
        for (long c : counts) total += c;
        if (total == 0) return 0;
        long target = Math.max(1, (long) Math.ceil(q * total));
        long cumulative = 0;
        for (int i = 0; i < counts.length; i++) {
            cumulative += counts[i];
            if (cumulative >= target) return upperBoundMicros(i);
        }
        return upperBoundMicros(counts.length - 1);
    }
}

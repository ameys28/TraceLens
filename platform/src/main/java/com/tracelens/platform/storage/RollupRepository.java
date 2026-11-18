package com.tracelens.platform.storage;

import com.tracelens.platform.core.LatencyHistogram;
import com.tracelens.platform.core.RollupAccumulator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Writes per-minute rollups with additive upserts and reads latency histograms back. */
@Repository
public class RollupRepository {
    static final String UPSERT_OP = """
            INSERT INTO op_rollup_minute (minute, service, operation, span_count, error_count, total_duration_us, max_duration_us)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (minute, service, operation) DO UPDATE SET
                span_count        = op_rollup_minute.span_count + EXCLUDED.span_count,
                error_count       = op_rollup_minute.error_count + EXCLUDED.error_count,
                total_duration_us = op_rollup_minute.total_duration_us + EXCLUDED.total_duration_us,
                max_duration_us   = GREATEST(op_rollup_minute.max_duration_us, EXCLUDED.max_duration_us)
            """;
    static final String UPSERT_BUCKET = """
            INSERT INTO op_latency_bucket_minute (minute, service, operation, bucket, count)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (minute, service, operation, bucket) DO UPDATE SET
                count = op_latency_bucket_minute.count + EXCLUDED.count
            """;
    static final String UPSERT_EDGE = """
            INSERT INTO edge_rollup_minute (minute, caller, callee, call_count, error_count, total_duration_us)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (minute, caller, callee) DO UPDATE SET
                call_count        = edge_rollup_minute.call_count + EXCLUDED.call_count,
                error_count       = edge_rollup_minute.error_count + EXCLUDED.error_count,
                total_duration_us = edge_rollup_minute.total_duration_us + EXCLUDED.total_duration_us
            """;
    static final String UPSERT_SAMPLING = """
            INSERT INTO sampling_stats_minute (minute, reason, trace_count, span_count)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (minute, reason) DO UPDATE SET
                trace_count = sampling_stats_minute.trace_count + EXCLUDED.trace_count,
                span_count  = sampling_stats_minute.span_count + EXCLUDED.span_count
            """;
    static final String BUCKETS_BY_OPERATION = """
            SELECT service, operation, bucket, SUM(count) AS c
            FROM op_latency_bucket_minute
            WHERE minute >= now() - make_interval(mins => ?)
            GROUP BY service, operation, bucket
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RollupRepository(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public static OffsetDateTime minute(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    public void write(RollupAccumulator.Snapshot snap) {
        List<Object[]> ops = new ArrayList<>();
        List<Object[]> buckets = new ArrayList<>();
        // sorted keys: concurrent upserts from several instances then lock rows in the same order (no deadlocks)
        snap.ops().entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<RollupAccumulator.OpKey, RollupAccumulator.OpStats> e) -> e.getKey().minuteEpochMs())
                        .thenComparing(e -> e.getKey().service()).thenComparing(e -> e.getKey().operation()))
                .forEach(e -> {
                    RollupAccumulator.OpKey k = e.getKey();
                    RollupAccumulator.OpStats v = e.getValue();
                    OffsetDateTime m = minute(k.minuteEpochMs());
                    ops.add(new Object[]{m, k.service(), k.operation(), v.count, v.errors, v.totalMicros, v.maxMicros});
                    for (int b = 0; b < v.buckets.length; b++) {
                        if (v.buckets[b] > 0) buckets.add(new Object[]{m, k.service(), k.operation(), (short) b, v.buckets[b]});
                    }
                });
        List<Object[]> edges = new ArrayList<>();
        snap.edges().forEach((k, v) -> edges.add(new Object[]{minute(k.minuteEpochMs()), k.caller(), k.callee(), v.calls, v.errors, v.totalMicros}));
        List<Object[]> sampling = new ArrayList<>();
        snap.sampling().forEach((k, v) -> sampling.add(new Object[]{minute(k.minuteEpochMs()), k.reason(), v.traces, v.spans}));

        tx.executeWithoutResult(status -> {
            if (!ops.isEmpty()) jdbc.batchUpdate(UPSERT_OP, ops);
            if (!buckets.isEmpty()) jdbc.batchUpdate(UPSERT_BUCKET, buckets);
            if (!edges.isEmpty()) jdbc.batchUpdate(UPSERT_EDGE, edges);
            if (!sampling.isEmpty()) jdbc.batchUpdate(UPSERT_SAMPLING, sampling);
        });
    }

    /** Merged latency histograms per {@code service|operation} over the last N minutes. */
    public Map<String, long[]> bucketsByOperation(int windowMinutes) {
        Map<String, long[]> out = new HashMap<>();
        jdbc.query(BUCKETS_BY_OPERATION, rs -> {
            String key = rs.getString("service") + "|" + rs.getString("operation");
            int bucket = rs.getInt("bucket");
            if (bucket >= 0 && bucket < LatencyHistogram.BUCKETS) {
                out.computeIfAbsent(key, k -> new long[LatencyHistogram.BUCKETS])[bucket] += rs.getLong("c");
            }
        }, windowMinutes);
        return out;
    }
}

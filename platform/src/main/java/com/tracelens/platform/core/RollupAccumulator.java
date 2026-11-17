package com.tracelens.platform.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory per-minute aggregates, fed with <b>every</b> trace before the sampling decision,
 * so RED metrics (rate, errors, duration) and the dependency graph stay accurate even
 * though only a fraction of traces is stored. Flushed to PostgreSQL periodically.
 */
public final class RollupAccumulator {

    public record OpKey(long minuteEpochMs, String service, String operation) {}
    public record EdgeKey(long minuteEpochMs, String caller, String callee) {}
    public record SamplingKey(long minuteEpochMs, String reason) {}

    public static final class OpStats {
        public long count, errors, totalMicros, maxMicros;
        public final long[] buckets = new long[LatencyHistogram.BUCKETS];

        void add(long micros, boolean error) {
            count++;
            if (error) errors++;
            totalMicros += micros;
            maxMicros = Math.max(maxMicros, micros);
            buckets[LatencyHistogram.bucketFor(micros)]++;
        }
    }

    public static final class EdgeStats {
        public long calls, errors, totalMicros;
    }

    public static final class SamplingStats {
        public long traces, spans;
    }

    public record Snapshot(Map<OpKey, OpStats> ops, Map<EdgeKey, EdgeStats> edges, Map<SamplingKey, SamplingStats> sampling) {
        public boolean isEmpty() { return ops.isEmpty() && edges.isEmpty() && sampling.isEmpty(); }
    }

    private Map<OpKey, OpStats> ops = new HashMap<>();
    private Map<EdgeKey, EdgeStats> edges = new HashMap<>();
    private Map<SamplingKey, SamplingStats> sampling = new HashMap<>();

    public static long minuteOf(long epochNanos) {
        long ms = epochNanos / 1_000_000;
        return ms - Math.floorMod(ms, 60_000L);
    }

    /** Records a trace (or late fragment of one): entry-span stats and cross-service edges. */
    public synchronized void record(List<SpanRecord> spans) {
        Map<String, SpanRecord> byId = new HashMap<>(spans.size() * 2);
        for (SpanRecord s : spans) byId.put(s.spanId(), s);
        for (SpanRecord s : spans) {
            long minute = minuteOf(s.startNanos());
            if (s.entrySpan()) {
                ops.computeIfAbsent(new OpKey(minute, s.serviceName(), s.name()), k -> new OpStats())
                        .add(s.durationMicros(), s.hasError());
            }
            SpanRecord parent = s.rootSpan() ? null : byId.get(s.parentSpanId());
            if (parent != null && !parent.serviceName().equals(s.serviceName())) {
                EdgeStats e = edges.computeIfAbsent(new EdgeKey(minute, parent.serviceName(), s.serviceName()), k -> new EdgeStats());
                e.calls++;
                if (s.hasError()) e.errors++;
                e.totalMicros += s.durationMicros();
            }
        }
    }

    public synchronized void recordSampling(long minuteEpochMs, String reason, int spanCount) {
        SamplingStats st = sampling.computeIfAbsent(new SamplingKey(minuteEpochMs, reason), k -> new SamplingStats());
        st.traces++;
        st.spans += spanCount;
    }

    /** Returns everything accumulated so far and starts fresh. */
    public synchronized Snapshot drain() {
        Snapshot snap = new Snapshot(ops, edges, sampling);
        ops = new HashMap<>();
        edges = new HashMap<>();
        sampling = new HashMap<>();
        return snap;
    }

    /** Puts a snapshot back (used when a flush to the database fails). */
    public synchronized void restore(Snapshot snap) {
        snap.ops().forEach((k, v) -> {
            OpStats cur = ops.computeIfAbsent(k, x -> new OpStats());
            cur.count += v.count;
            cur.errors += v.errors;
            cur.totalMicros += v.totalMicros;
            cur.maxMicros = Math.max(cur.maxMicros, v.maxMicros);
            for (int i = 0; i < cur.buckets.length; i++) cur.buckets[i] += v.buckets[i];
        });
        snap.edges().forEach((k, v) -> {
            EdgeStats cur = edges.computeIfAbsent(k, x -> new EdgeStats());
            cur.calls += v.calls;
            cur.errors += v.errors;
            cur.totalMicros += v.totalMicros;
        });
        snap.sampling().forEach((k, v) -> {
            SamplingStats cur = sampling.computeIfAbsent(k, x -> new SamplingStats());
            cur.traces += v.traces;
            cur.spans += v.spans;
        });
    }
}

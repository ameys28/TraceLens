package com.tracelens.platform.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracelens.platform.core.AnomalyDetector;
import com.tracelens.platform.core.LatencyHistogram;
import com.tracelens.platform.core.SpanRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** Read side: everything the UI and the alert evaluator need. */
@Repository
public class QueryRepository {

    // ------------------------------------------------------------------ DTOs
    public record TraceSummary(String traceId, long startEpochMs, long durationUs, String rootService, String rootOperation,
                               int spanCount, int errorCount, List<String> services, String samplingReason) {}

    public record LatencyStats(long requests, long errors, double errorRate, double rps,
                               long avgUs, long p50Us, long p95Us, long p99Us, long maxUs) {}

    public record ServiceStats(String service, LatencyStats stats) {}

    public record OperationStats(String service, String operation, LatencyStats stats) {}

    public record TimePoint(long minuteEpochMs, long requests, long errors, long p50Us, long p95Us, long p99Us) {}

    public record Edge(String caller, String callee, long calls, long errors, double errorRate, long avgUs) {}

    public record SamplingReasonStats(String reason, long traces, long spans) {}

    public record SamplingPoint(long minuteEpochMs, String reason, long traces) {}

    public record Partition(String name, long bytes, long estimatedRows) {}

    // ------------------------------------------------------------------ SQL
    static final String SPANS_OF_TRACE = """
            SELECT trace_id, span_id, parent_span_id, service_name, operation, kind, start_us, duration_us,
                   status_code, status_message, attributes::text AS attributes, events::text AS events
            FROM spans WHERE trace_id = ?
            """;
    static final String TRACE_REASON = """
            SELECT sampling_reason FROM traces WHERE trace_id = ? ORDER BY start_time LIMIT 1
            """;
    static final String STATS_BY_SERVICE = """
            SELECT service AS k1, '' AS k2, SUM(span_count) AS req, SUM(error_count) AS err,
                   SUM(total_duration_us) AS tot, MAX(max_duration_us) AS mx
            FROM op_rollup_minute WHERE minute >= now() - make_interval(mins => ?)
            GROUP BY service
            """;
    static final String BUCKETS_BY_SERVICE = """
            SELECT service AS k1, '' AS k2, bucket, SUM(count) AS c
            FROM op_latency_bucket_minute WHERE minute >= now() - make_interval(mins => ?)
            GROUP BY service, bucket
            """;
    static final String STATS_BY_OPERATION = """
            SELECT service AS k1, operation AS k2, SUM(span_count) AS req, SUM(error_count) AS err,
                   SUM(total_duration_us) AS tot, MAX(max_duration_us) AS mx
            FROM op_rollup_minute
            WHERE minute >= now() - make_interval(mins => ?) AND (?::text IS NULL OR service = ?::text)
            GROUP BY service, operation
            """;
    static final String OP_BUCKETS = """
            SELECT service AS k1, operation AS k2, bucket, SUM(count) AS c
            FROM op_latency_bucket_minute
            WHERE minute >= now() - make_interval(mins => ?) AND (?::text IS NULL OR service = ?::text)
            GROUP BY service, operation, bucket
            """;
    static final String WINDOW_STATS = """
            SELECT service AS k1, operation AS k2, SUM(span_count) AS req, SUM(error_count) AS err,
                   SUM(total_duration_us) AS tot, MAX(max_duration_us) AS mx
            FROM op_rollup_minute
            WHERE minute >= now() - make_interval(mins => ?) AND minute < now() - make_interval(mins => ?)
            GROUP BY service, operation
            """;
    static final String WINDOW_BUCKETS = """
            SELECT service AS k1, operation AS k2, bucket, SUM(count) AS c
            FROM op_latency_bucket_minute
            WHERE minute >= now() - make_interval(mins => ?) AND minute < now() - make_interval(mins => ?)
            GROUP BY service, operation, bucket
            """;
    static final String TIMESERIES = """
            SELECT EXTRACT(EPOCH FROM minute)::bigint * 1000 AS m, SUM(span_count) AS req, SUM(error_count) AS err
            FROM op_rollup_minute
            WHERE service = ? AND (?::text IS NULL OR operation = ?::text) AND minute >= now() - make_interval(mins => ?)
            GROUP BY minute ORDER BY minute
            """;
    static final String TIMESERIES_BUCKETS = """
            SELECT EXTRACT(EPOCH FROM minute)::bigint * 1000 AS m, bucket, SUM(count) AS c
            FROM op_latency_bucket_minute
            WHERE service = ? AND (?::text IS NULL OR operation = ?::text) AND minute >= now() - make_interval(mins => ?)
            GROUP BY minute, bucket
            """;
    static final String EDGES = """
            SELECT caller, callee, SUM(call_count) AS calls, SUM(error_count) AS err, SUM(total_duration_us) AS tot
            FROM edge_rollup_minute WHERE minute >= now() - make_interval(mins => ?)
            GROUP BY caller, callee ORDER BY caller, callee
            """;
    static final String SAMPLING_TOTALS = """
            SELECT reason, SUM(trace_count) AS traces, SUM(span_count) AS spans
            FROM sampling_stats_minute WHERE minute >= now() - make_interval(mins => ?)
            GROUP BY reason ORDER BY reason
            """;
    static final String SAMPLING_SERIES = """
            SELECT EXTRACT(EPOCH FROM minute)::bigint * 1000 AS m, reason, trace_count
            FROM sampling_stats_minute WHERE minute >= now() - make_interval(mins => ?)
            ORDER BY minute, reason
            """;
    static final String PARTITIONS = """
            SELECT c.relname AS name, pg_total_relation_size(c.oid) AS bytes, GREATEST(c.reltuples, 0)::bigint AS rows
            FROM pg_inherits i
            JOIN pg_class c ON c.oid = i.inhrelid
            JOIN pg_class p ON p.oid = i.inhparent
            WHERE p.relname = 'spans'
            ORDER BY c.relname
            """;
    static final String TOP_ERROR = """
            SELECT status_message, COUNT(*) AS n, MIN(trace_id) AS example
            FROM spans
            WHERE service_name = ? AND status_code = 'ERROR' AND start_time >= now() - make_interval(mins => ?)
            GROUP BY status_message ORDER BY n DESC LIMIT 1
            """;
    static final String SLOWEST_TRACE = """
            SELECT trace_id FROM spans
            WHERE service_name = ? AND operation = ? AND start_time >= now() - make_interval(mins => ?)
            ORDER BY duration_us DESC LIMIT 1
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public QueryRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ traces
    public List<TraceSummary> searchTraces(String service, String operation, String status,
                                           Long minDurationMs, Long maxDurationMs, int lookbackMinutes, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT trace_id, start_us / 1000 AS start_ms,
                       duration_us, root_service, root_operation, span_count, error_count, services, sampling_reason
                FROM traces WHERE start_time >= now() - make_interval(mins => ?)
                """);
        List<Object> args = new ArrayList<>();
        args.add(lookbackMinutes);
        if (service != null && !service.isBlank()) {
            sql.append(" AND services @> ARRAY[?]::text[]");
            args.add(service);
        }
        if (operation != null && !operation.isBlank()) {
            sql.append(" AND root_operation ILIKE ?");
            args.add("%" + operation + "%");
        }
        if ("error".equalsIgnoreCase(status)) sql.append(" AND error_count > 0");
        if ("ok".equalsIgnoreCase(status)) sql.append(" AND error_count = 0");
        if (minDurationMs != null) {
            sql.append(" AND duration_us >= ?");
            args.add(minDurationMs * 1000);
        }
        if (maxDurationMs != null) {
            sql.append(" AND duration_us <= ?");
            args.add(maxDurationMs * 1000);
        }
        sql.append(" ORDER BY start_time DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> new TraceSummary(
                rs.getString("trace_id"), rs.getLong("start_ms"), rs.getLong("duration_us"),
                rs.getString("root_service"), rs.getString("root_operation"),
                rs.getInt("span_count"), rs.getInt("error_count"), textArray(rs.getArray("services")),
                rs.getString("sampling_reason")), args.toArray());
    }

    public List<SpanRecord> spansOfTrace(String traceId) {
        return jdbc.query(SPANS_OF_TRACE, (rs, i) -> {
            long startUs = rs.getLong("start_us");
            long durUs = rs.getLong("duration_us");
            return new SpanRecord(
                    rs.getString("trace_id").trim(), rs.getString("span_id").trim(), rs.getString("parent_span_id").trim(),
                    rs.getString("service_name"), rs.getString("operation"), rs.getString("kind"),
                    startUs * 1000, (startUs + durUs) * 1000,
                    rs.getString("status_code"), rs.getString("status_message"),
                    readJson(rs.getString("attributes"), new TypeReference<Map<String, Object>>() {}, Map.of()),
                    readJson(rs.getString("events"), new TypeReference<List<SpanRecord.SpanEvent>>() {}, List.of()));
        }, traceId);
    }

    public Optional<String> samplingReason(String traceId) {
        return jdbc.query(TRACE_REASON, (rs, i) -> rs.getString(1), traceId).stream().findFirst();
    }

    // ------------------------------------------------------------------ RED metrics
    public List<ServiceStats> serviceStats(int windowMinutes) {
        Map<String, long[]> buckets = buckets(BUCKETS_BY_SERVICE, windowMinutes);
        return jdbc.query(STATS_BY_SERVICE, (rs, i) -> new ServiceStats(rs.getString("k1"),
                latency(rs, buckets.get(rs.getString("k1") + "|"), windowMinutes)), windowMinutes)
                .stream().sorted(Comparator.comparing(ServiceStats::service)).toList();
    }

    public List<OperationStats> operationStats(String service, int windowMinutes) {
        Map<String, long[]> buckets = buckets(OP_BUCKETS, windowMinutes, service, service);
        return jdbc.query(STATS_BY_OPERATION, (rs, i) -> new OperationStats(rs.getString("k1"), rs.getString("k2"),
                        latency(rs, buckets.get(rs.getString("k1") + "|" + rs.getString("k2")), windowMinutes)),
                windowMinutes, service, service)
                .stream().sorted(Comparator.comparing((OperationStats o) -> o.stats().requests()).reversed()).toList();
    }

    /** Stats per {@code service|operation} for the window [fromMinutesAgo, toMinutesAgo). */
    public Map<String, AnomalyDetector.WindowStats> windowStats(int fromMinutesAgo, int toMinutesAgo) {
        Map<String, long[]> buckets = buckets(WINDOW_BUCKETS, fromMinutesAgo, toMinutesAgo);
        Map<String, AnomalyDetector.WindowStats> out = new HashMap<>();
        jdbc.query(WINDOW_STATS, rs -> {
            String key = rs.getString("k1") + "|" + rs.getString("k2");
            long[] b = buckets.getOrDefault(key, new long[LatencyHistogram.BUCKETS]);
            out.put(key, new AnomalyDetector.WindowStats(rs.getLong("req"), rs.getLong("err"), LatencyHistogram.percentile(b, 0.95)));
        }, fromMinutesAgo, toMinutesAgo);
        return out;
    }

    public List<TimePoint> timeseries(String service, String operation, int windowMinutes) {
        Map<Long, long[]> buckets = new HashMap<>();
        jdbc.query(TIMESERIES_BUCKETS, rs -> {
            int b = rs.getInt("bucket");
            if (b >= 0 && b < LatencyHistogram.BUCKETS)
                buckets.computeIfAbsent(rs.getLong("m"), k -> new long[LatencyHistogram.BUCKETS])[b] += rs.getLong("c");
        }, service, operation, operation, windowMinutes);
        return jdbc.query(TIMESERIES, (rs, i) -> {
            long m = rs.getLong("m");
            long[] b = buckets.getOrDefault(m, new long[LatencyHistogram.BUCKETS]);
            return new TimePoint(m, rs.getLong("req"), rs.getLong("err"),
                    LatencyHistogram.percentile(b, 0.50), LatencyHistogram.percentile(b, 0.95), LatencyHistogram.percentile(b, 0.99));
        }, service, operation, operation, windowMinutes);
    }

    public List<Edge> edges(int windowMinutes) {
        return jdbc.query(EDGES, (rs, i) -> {
            long calls = rs.getLong("calls");
            long err = rs.getLong("err");
            return new Edge(rs.getString("caller"), rs.getString("callee"), calls, err,
                    calls == 0 ? 0 : (double) err / calls, calls == 0 ? 0 : rs.getLong("tot") / calls);
        }, windowMinutes);
    }

    // ------------------------------------------------------------------ sampling & storage
    public List<SamplingReasonStats> samplingTotals(int windowMinutes) {
        return jdbc.query(SAMPLING_TOTALS, (rs, i) -> new SamplingReasonStats(rs.getString("reason"), rs.getLong("traces"), rs.getLong("spans")), windowMinutes);
    }

    public List<SamplingPoint> samplingSeries(int windowMinutes) {
        return jdbc.query(SAMPLING_SERIES, (rs, i) -> new SamplingPoint(rs.getLong("m"), rs.getString("reason"), rs.getLong("trace_count")), windowMinutes);
    }

    public List<Partition> partitions() {
        return jdbc.query(PARTITIONS, (rs, i) -> new Partition(rs.getString("name"), rs.getLong("bytes"), rs.getLong("rows")));
    }

    // ------------------------------------------------------------------ alert context
    /** Most common error message for a service in the last N minutes, with an example trace. */
    public Optional<String[]> topError(String service, int windowMinutes) {
        return jdbc.query(TOP_ERROR, (rs, i) -> new String[]{rs.getString("status_message"), rs.getString("example")},
                service, windowMinutes).stream().findFirst();
    }

    public Optional<String> slowestTrace(String service, String operation, int windowMinutes) {
        return jdbc.query(SLOWEST_TRACE, (rs, i) -> rs.getString(1), service, operation, windowMinutes).stream().findFirst();
    }

    // ------------------------------------------------------------------ helpers
    private Map<String, long[]> buckets(String sql, Object... args) {
        Map<String, long[]> out = new HashMap<>();
        jdbc.query(sql, rs -> {
            int b = rs.getInt("bucket");
            if (b >= 0 && b < LatencyHistogram.BUCKETS) {
                String key = rs.getString("k1") + "|" + rs.getString("k2");
                out.computeIfAbsent(key, k -> new long[LatencyHistogram.BUCKETS])[b] += rs.getLong("c");
            }
        }, args);
        return out;
    }

    private static LatencyStats latency(ResultSet rs, long[] buckets, int windowMinutes) throws SQLException {
        long req = rs.getLong("req");
        long err = rs.getLong("err");
        long[] b = buckets == null ? new long[LatencyHistogram.BUCKETS] : buckets;
        return new LatencyStats(req, err, req == 0 ? 0 : (double) err / req, req / (windowMinutes * 60.0),
                req == 0 ? 0 : rs.getLong("tot") / req,
                LatencyHistogram.percentile(b, 0.50), LatencyHistogram.percentile(b, 0.95),
                LatencyHistogram.percentile(b, 0.99), rs.getLong("mx"));
    }

    private static List<String> textArray(Array array) throws SQLException {
        if (array == null) return List.of();
        return Arrays.asList((String[]) array.getArray());
    }

    private <T> T readJson(String json, TypeReference<T> type, T fallback) {
        if (json == null) return fallback;
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            return fallback;
        }
    }
}

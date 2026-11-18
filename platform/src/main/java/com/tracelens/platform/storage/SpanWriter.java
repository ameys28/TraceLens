package com.tracelens.platform.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracelens.platform.core.SpanRecord;
import com.tracelens.platform.core.TailSamplingPolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Buffers kept traces and writes them with JDBC batch inserts (one round trip per batch,
 * not per row). Spans are idempotent (ON CONFLICT DO NOTHING) because Kafka is at-least-once.
 */
@Component
public class SpanWriter {
    static final String INSERT_SPAN = """
            INSERT INTO spans (trace_id, span_id, parent_span_id, service_name, operation, kind,
                               start_time, start_us, duration_us, status_code, status_message, attributes, events)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            ON CONFLICT DO NOTHING
            """;
    static final String INSERT_TRACE = """
            INSERT INTO traces (trace_id, start_time, start_us, duration_us, root_service, root_operation,
                                span_count, error_count, services, sampling_reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (trace_id, start_time) DO UPDATE SET
                span_count  = traces.span_count + EXCLUDED.span_count,
                error_count = traces.error_count + EXCLUDED.error_count,
                duration_us = GREATEST(traces.duration_us, EXCLUDED.duration_us)
            """;
    static final String UPDATE_TRACE_LATE = """
            UPDATE traces SET span_count = span_count + ?, error_count = error_count + ?,
                              services = ARRAY(SELECT DISTINCT unnest(services || ?::text[]))
            WHERE trace_id = ?
            """;

    private record Pending(List<SpanRecord> spans, TailSamplingPolicy.Reason reason, boolean late) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final Counter spansWritten;
    private final List<Pending> pending = new ArrayList<>();

    public SpanWriter(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, MeterRegistry registry) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.spansWritten = Counter.builder("tracelens.storage.spans_written").register(registry);
    }

    public synchronized void add(List<SpanRecord> spans, TailSamplingPolicy.Reason reason, boolean late) {
        pending.add(new Pending(spans, reason, late));
    }

    public synchronized void flush() {
        if (pending.isEmpty()) return;
        List<Pending> batch = new ArrayList<>(pending);
        pending.clear();

        List<Object[]> spanRows = new ArrayList<>();
        List<Object[]> traceRows = new ArrayList<>();
        List<Object[]> lateRows = new ArrayList<>();
        for (Pending p : batch) {
            for (SpanRecord s : p.spans()) spanRows.add(spanRow(s));
            int errors = (int) p.spans().stream().filter(SpanRecord::hasError).count();
            String[] services = p.spans().stream().map(SpanRecord::serviceName)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)).toArray(String[]::new);
            if (p.late()) {
                lateRows.add(new Object[]{p.spans().size(), errors, services, p.spans().get(0).traceId()});
            } else {
                SpanRecord root = TailSamplingPolicy.rootOf(p.spans());
                long startNanos = p.spans().stream().mapToLong(SpanRecord::startNanos).min().orElse(root.startNanos());
                traceRows.add(new Object[]{
                        root.traceId(), ts(startNanos), startNanos / 1000,
                        TailSamplingPolicy.traceDurationMicros(p.spans()),
                        root.serviceName(), root.name(), p.spans().size(), errors, services, p.reason().name()});
            }
        }

        tx.executeWithoutResult(status -> {
            jdbc.batchUpdate(INSERT_SPAN, spanRows);
            if (!traceRows.isEmpty()) {
                jdbc.batchUpdate(INSERT_TRACE, traceRows, traceRows.size(), (ps, row) -> {
                    for (int i = 0; i < row.length; i++) {
                        if (row[i] instanceof String[] arr) ps.setArray(i + 1, ps.getConnection().createArrayOf("text", arr));
                        else ps.setObject(i + 1, row[i]);
                    }
                });
            }
            if (!lateRows.isEmpty()) {
                jdbc.batchUpdate(UPDATE_TRACE_LATE, lateRows, lateRows.size(), (ps, row) -> {
                    ps.setInt(1, (Integer) row[0]);
                    ps.setInt(2, (Integer) row[1]);
                    ps.setArray(3, ps.getConnection().createArrayOf("text", (String[]) row[2]));
                    ps.setString(4, (String) row[3]);
                });
            }
        });
        spansWritten.increment(spanRows.size());
    }

    private Object[] spanRow(SpanRecord s) {
        return new Object[]{
                s.traceId(), s.spanId(), s.parentSpanId() == null ? "" : s.parentSpanId(),
                s.serviceName(), s.name(), s.kind(),
                ts(s.startNanos()), s.startMicros(), s.durationMicros(),
                s.statusCode(), s.statusMessage() == null ? "" : s.statusMessage(),
                json(s.attributes() == null ? java.util.Map.of() : s.attributes()),
                json(s.events() == null ? List.of() : s.events())};
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    static OffsetDateTime ts(long epochNanos) {
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(0, epochNanos), ZoneOffset.UTC);
    }
}

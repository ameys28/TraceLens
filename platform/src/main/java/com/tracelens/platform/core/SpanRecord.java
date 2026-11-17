package com.tracelens.platform.core;

import java.util.List;
import java.util.Map;

/**
 * Internal, normalized span. Produced by {@link OtlpJsonParser}, travels through Kafka
 * and Redis as JSON, and is written to PostgreSQL.
 *
 * @param kind        INTERNAL | SERVER | CLIENT | PRODUCER | CONSUMER | UNSPECIFIED
 * @param statusCode  UNSET | OK | ERROR
 */
public record SpanRecord(
        String traceId,
        String spanId,
        String parentSpanId,
        String serviceName,
        String name,
        String kind,
        long startNanos,
        long endNanos,
        String statusCode,
        String statusMessage,
        Map<String, Object> attributes,
        List<SpanEvent> events) {

    public long durationMicros() {
        return Math.max(0, (endNanos - startNanos) / 1_000);
    }

    public long startMicros() {
        return startNanos / 1_000;
    }

    public boolean hasError() {
        return "ERROR".equals(statusCode);
    }

    public boolean rootSpan() {
        return parentSpanId == null || parentSpanId.isEmpty();
    }

    /** Entry point into a service: where request latency/error stats are measured. */
    public boolean entrySpan() {
        return "SERVER".equals(kind) || "CONSUMER".equals(kind) || rootSpan();
    }

    public record SpanEvent(String name, long timeNanos, Map<String, Object> attributes) {}
}

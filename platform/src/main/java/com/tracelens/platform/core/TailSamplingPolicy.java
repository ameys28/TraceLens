package com.tracelens.platform.core;

import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Tail-based sampling: decided once the whole trace has been seen.
 * <ol>
 *   <li>Any span with ERROR status -> keep (reason ERROR)</li>
 *   <li>Trace slower than the p99 of its root operation -> keep (reason SLOW)</li>
 *   <li>Otherwise keep a deterministic {@code baselineRate} fraction by trace ID (reason BASELINE)</li>
 * </ol>
 * The baseline decision hashes the trace ID, so every instance makes the same choice
 * for the same trace and fragments of one trace are never split.
 */
public final class TailSamplingPolicy {

    public enum Reason { ERROR, SLOW, BASELINE, DROPPED, LATE_KEPT, LATE_DROPPED }

    public record Decision(boolean keep, Reason reason) {}

    private final double baselineRate;

    public TailSamplingPolicy(double baselineRate) {
        this.baselineRate = baselineRate;
    }

    /**
     * @param slowThresholdMicros returns the slow threshold for a root operation key ({@code service|operation})
     */
    public Decision decide(List<SpanRecord> spans, ToLongFunction<String> slowThresholdMicros) {
        if (spans.isEmpty()) return new Decision(false, Reason.DROPPED);
        for (SpanRecord s : spans) if (s.hasError()) return new Decision(true, Reason.ERROR);

        SpanRecord root = rootOf(spans);
        // Compare like with like: thresholds come from entry-span latency, so use the root span's
        // own duration (async work after the response, e.g. Kafka consumers, is not user latency).
        long duration = root.rootSpan() ? root.durationMicros() : traceDurationMicros(spans);
        long threshold = slowThresholdMicros.applyAsLong(root.serviceName() + "|" + root.name());
        if (threshold > 0 && duration >= threshold) return new Decision(true, Reason.SLOW);

        if (traceIdRatio(root.traceId()) < baselineRate) return new Decision(true, Reason.BASELINE);
        return new Decision(false, Reason.DROPPED);
    }

    /** The span with no parent (earliest if several), else the earliest span. */
    public static SpanRecord rootOf(List<SpanRecord> spans) {
        return spans.stream()
                .min(Comparator.<SpanRecord, Boolean>comparing(s -> !s.rootSpan()).thenComparingLong(SpanRecord::startNanos))
                .orElseThrow();
    }

    public static long traceDurationMicros(List<SpanRecord> spans) {
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (SpanRecord s : spans) {
            min = Math.min(min, s.startNanos());
            max = Math.max(max, s.endNanos());
        }
        return Math.max(0, (max - min) / 1_000);
    }

    /** Maps a trace ID to [0,1) using its low 64 bits (same idea as OTel's TraceIdRatioBased). */
    public static double traceIdRatio(String traceId) {
        long bits;
        try {
            String low = traceId.length() >= 16 ? traceId.substring(traceId.length() - 16) : traceId;
            bits = Long.parseUnsignedLong(low, 16);
        } catch (NumberFormatException e) {
            bits = traceId.hashCode() * 0x9E3779B97F4A7C15L;
        }
        return (bits >>> 11) * 0x1.0p-53;
    }
}

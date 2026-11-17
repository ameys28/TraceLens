package com.tracelens.platform.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.tracelens.platform.core.TestSpans.ok;
import static com.tracelens.platform.core.TestSpans.span;
import static org.assertj.core.api.Assertions.assertThat;

class TraceAssemblerTest {

    private static Map<String, TraceAssembler.AssembledSpan> byId(TraceAssembler.AssembledTrace t) {
        return t.spans().stream().collect(Collectors.toMap(TraceAssembler.AssembledSpan::spanId, Function.identity()));
    }

    /**
     * gateway [0..100]
     *   client->order [5..95]
     *     order server [10..90]
     *       inventory call [15..35]
     *       payment call   [40..85]   <- last to finish, on the critical path
     */
    private static List<SpanRecord> checkout() {
        return List.of(
                ok("a", "", "gateway", "POST /api/checkout", "SERVER", 0, 100),
                ok("b", "a", "gateway", "POST", "CLIENT", 5, 95),
                ok("c", "b", "order", "POST /orders", "SERVER", 10, 90),
                ok("d", "c", "order", "POST", "CLIENT", 15, 35),
                ok("e", "d", "inventory", "POST /inventory/reserve", "SERVER", 16, 34),
                ok("f", "c", "order", "POST", "CLIENT", 40, 85),
                ok("g", "f", "payment", "POST /payments/charge", "SERVER", 41, 84));
    }

    @Test
    void buildsTreeWithDepthsAndSummary() {
        TraceAssembler.AssembledTrace t = TraceAssembler.assemble(checkout());
        assertThat(t.spanCount()).isEqualTo(7);
        assertThat(t.durationMicros()).isEqualTo(100_000L);
        assertThat(t.rootService()).isEqualTo("gateway");
        assertThat(t.services()).containsExactly("gateway", "order", "inventory", "payment");
        Map<String, TraceAssembler.AssembledSpan> s = byId(t);
        assertThat(s.get("a").depth()).isEqualTo(0);
        assertThat(s.get("g").depth()).isEqualTo(4);
        // display order is depth-first by start time
        assertThat(t.spans().stream().map(TraceAssembler.AssembledSpan::spanId).toList())
                .containsExactly("a", "b", "c", "d", "e", "f", "g");
    }

    @Test
    void computesSelfTime() {
        Map<String, TraceAssembler.AssembledSpan> s = byId(TraceAssembler.assemble(checkout()));
        // order server 80ms, children cover 20 + 45 = 65ms
        assertThat(s.get("c").selfTimeMicros()).isEqualTo(15_000L);
        assertThat(s.get("g").selfTimeMicros()).isEqualTo(43_000L);
    }

    @Test
    void criticalPathFollowsTheLastFinishingChildAndSumsToRootDuration() {
        TraceAssembler.AssembledTrace t = TraceAssembler.assemble(checkout());
        Map<String, TraceAssembler.AssembledSpan> s = byId(t);
        assertThat(s.get("g").onCriticalPath()).isTrue();
        assertThat(s.get("e").onCriticalPath()).isTrue(); // sequential, so it is on the path too
        long total = t.spans().stream().mapToLong(TraceAssembler.AssembledSpan::criticalPathMicros).sum();
        assertThat(total).isEqualTo(100_000L);
        assertThat(t.bottleneck().spanId()).isEqualTo("g");
    }

    @Test
    void parallelChildrenOnlyTheSlowerOneIsCritical() {
        List<SpanRecord> spans = List.of(
                ok("r", "", "api", "GET /x", "SERVER", 0, 100),
                ok("fast", "r", "api", "fast", "INTERNAL", 10, 30),
                ok("slow", "r", "api", "slow", "INTERNAL", 10, 90));
        Map<String, TraceAssembler.AssembledSpan> s = byId(TraceAssembler.assemble(spans));
        assertThat(s.get("slow").onCriticalPath()).isTrue();
        assertThat(s.get("fast").onCriticalPath()).isFalse();
        assertThat(s.get("r").criticalPathMicros()).isEqualTo(20_000L);
    }

    @Test
    void correctsClockSkewForSynchronousCalls() {
        // payment's clock is 50ms behind: its server span appears to start before the client call
        List<SpanRecord> spans = List.of(
                ok("a", "", "order", "POST /orders", "SERVER", 0, 100),
                ok("b", "a", "order", "POST", "CLIENT", 10, 60),
                ok("c", "b", "payment", "charge", "SERVER", -38, 8),
                ok("d", "c", "payment", "db", "INTERNAL", -30, 0));
        Map<String, TraceAssembler.AssembledSpan> s = byId(TraceAssembler.assemble(spans));
        TraceAssembler.AssembledSpan c = s.get("c");
        assertThat(c.skewAdjustmentMicros()).isEqualTo(50_000L);
        assertThat(c.startOffsetMicros()).isEqualTo(12_000L); // centred in the 50ms client span
        // the subtree moves with it
        assertThat(s.get("d").startOffsetMicros()).isEqualTo(20_000L);
        assertThat(s.get("d").skewAdjustmentMicros()).isEqualTo(50_000L);
    }

    @Test
    void doesNotShiftAsyncConsumers() {
        List<SpanRecord> spans = List.of(
                ok("a", "", "order", "POST /orders", "SERVER", 0, 50),
                ok("p", "a", "order", "order-events publish", "PRODUCER", 40, 45),
                ok("c", "p", "notification", "order-events process", "CONSUMER", 70, 120));
        TraceAssembler.AssembledTrace t = TraceAssembler.assemble(spans);
        TraceAssembler.AssembledSpan c = byId(t).get("c");
        assertThat(c.skewAdjustmentMicros()).isEqualTo(0L);
        assertThat(c.onCriticalPath()).isFalse(); // happens after the response
        assertThat(t.durationMicros()).isEqualTo(120_000L);
    }

    @Test
    void marksOrphansWhenParentIsMissing() {
        List<SpanRecord> spans = List.of(
                ok("a", "", "gateway", "GET /", "SERVER", 0, 10),
                ok("x", "missing", "worker", "job", "SERVER", 2, 5));
        TraceAssembler.AssembledTrace t = TraceAssembler.assemble(spans);
        assertThat(byId(t).get("x").orphan()).isTrue();
        assertThat(t.rootService()).isEqualTo("gateway");
    }

    @Test
    void rootCauseIsTheDeepestFailingSpan() {
        List<SpanRecord> spans = List.of(
                span("a", "", "gateway", "POST /api/checkout", "SERVER", 0, 100, true),
                span("b", "a", "order", "POST /orders", "SERVER", 10, 90, true),
                span("c", "b", "payment", "POST /payments/charge", "SERVER", 20, 80, true));
        TraceAssembler.AssembledTrace t = TraceAssembler.assemble(spans);
        assertThat(t.errorCount()).isEqualTo(3);
        assertThat(t.rootCause().serviceName()).isEqualTo("payment");
    }

    @Test
    void survivesParentCycles() {
        List<SpanRecord> spans = List.of(
                ok("a", "b", "s", "x", "INTERNAL", 0, 10),
                ok("b", "a", "s", "y", "INTERNAL", 1, 5));
        TraceAssembler.AssembledTrace t = TraceAssembler.assemble(spans);
        assertThat(t.spanCount()).isEqualTo(2);
    }
}

package com.tracelens.platform.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tracelens.platform.core.TestSpans.ok;
import static com.tracelens.platform.core.TestSpans.span;
import static org.assertj.core.api.Assertions.assertThat;

class RollupAndAnomalyTest {

    @Test
    void rollupCountsEntrySpansAndCrossServiceEdges() {
        RollupAccumulator acc = new RollupAccumulator();
        acc.record(List.of(
                ok("a", "", "gateway", "POST /api/checkout", "SERVER", 0, 100),
                ok("b", "a", "gateway", "POST", "CLIENT", 5, 95),
                span("c", "b", "order", "POST /orders", "SERVER", 10, 90, true),
                ok("d", "c", "order", "order.price", "INTERNAL", 11, 20)));
        RollupAccumulator.Snapshot snap = acc.drain();
        assertThat(snap.ops()).hasSize(2); // two entry spans; CLIENT/INTERNAL are not entries
        assertThat(snap.edges()).hasSize(1);
        RollupAccumulator.EdgeStats edge = snap.edges().values().iterator().next();
        assertThat(edge.calls).isEqualTo(1L);
        assertThat(edge.errors).isEqualTo(1L);
        assertThat(acc.drain().isEmpty()).isTrue();
    }

    @Test
    void minuteBucketing() {
        long ns = 125_000L * 1_000_000L; // 125s
        assertThat(RollupAccumulator.minuteOf(ns)).isEqualTo(120_000L);
    }

    @Test
    void detectsErrorSpikeAndLatencyRegression() {
        AnomalyDetector d = new AnomalyDetector(AnomalyDetector.Thresholds.defaults());
        var findings = d.evaluate("payment", "POST /charge",
                new AnomalyDetector.WindowStats(100, 30, 900_000),
                new AnomalyDetector.WindowStats(1000, 5, 80_000));
        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).type()).isEqualTo(AnomalyDetector.Type.ERROR_RATE);
        assertThat(findings.get(0).severity()).isEqualTo(AnomalyDetector.Severity.CRITICAL);
        assertThat(findings.get(1).type()).isEqualTo(AnomalyDetector.Type.LATENCY);
    }

    @Test
    void quietWhenHealthyOrTooFewRequests() {
        AnomalyDetector d = new AnomalyDetector(AnomalyDetector.Thresholds.defaults());
        assertThat(d.evaluate("s", "op", new AnomalyDetector.WindowStats(100, 1, 50_000),
                new AnomalyDetector.WindowStats(1000, 10, 45_000))).hasSize(0);
        assertThat(d.evaluate("s", "op", new AnomalyDetector.WindowStats(3, 3, 5_000_000),
                new AnomalyDetector.WindowStats(1000, 0, 45_000))).hasSize(0);
    }
}

package com.tracelens.platform.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tracelens.platform.core.TestSpans.ok;
import static com.tracelens.platform.core.TestSpans.span;
import static org.assertj.core.api.Assertions.assertThat;

class TailSamplingPolicyTest {

    @Test
    void keepsErrorsEvenAtZeroBaseline() {
        TailSamplingPolicy policy = new TailSamplingPolicy(0.0);
        var d = policy.decide(List.of(
                ok("a", "", "gw", "GET /", "SERVER", 0, 10),
                span("b", "a", "db", "q", "CLIENT", 1, 5, true)), k -> 1_000_000);
        assertThat(d.keep()).isTrue();
        assertThat(d.reason()).isEqualTo(TailSamplingPolicy.Reason.ERROR);
    }

    @Test
    void keepsTracesSlowerThanTheOperationThreshold() {
        TailSamplingPolicy policy = new TailSamplingPolicy(0.0);
        var d = policy.decide(List.of(ok("a", "", "gw", "GET /", "SERVER", 0, 800)),
                key -> key.equals("gw|GET /") ? 500_000 : 0);
        assertThat(d.reason()).isEqualTo(TailSamplingPolicy.Reason.SLOW);
    }

    @Test
    void dropsFastHealthyTracesAtZeroBaseline() {
        TailSamplingPolicy policy = new TailSamplingPolicy(0.0);
        var d = policy.decide(List.of(ok("a", "", "gw", "GET /", "SERVER", 0, 20)), k -> 500_000);
        assertThat(d.keep()).isFalse();
        assertThat(d.reason()).isEqualTo(TailSamplingPolicy.Reason.DROPPED);
    }

    @Test
    void keepsEverythingAtFullBaseline() {
        TailSamplingPolicy policy = new TailSamplingPolicy(1.0);
        var d = policy.decide(List.of(ok("a", "", "gw", "GET /", "SERVER", 0, 20)), k -> 500_000);
        assertThat(d.reason()).isEqualTo(TailSamplingPolicy.Reason.BASELINE);
    }

    @Test
    void traceIdRatioIsDeterministicAndRoughlyUniform() {
        assertThat(TailSamplingPolicy.traceIdRatio("4bf92f3577b34da6a3ce929d0e0e4736"))
                .isEqualTo(TailSamplingPolicy.traceIdRatio("4bf92f3577b34da6a3ce929d0e0e4736"));
        java.util.Random rnd = new java.util.Random(42);
        int kept = 0, n = 20_000;
        for (int i = 0; i < n; i++) {
            String id = String.format("%016x%016x", rnd.nextLong(), rnd.nextLong());
            if (TailSamplingPolicy.traceIdRatio(id) < 0.1) kept++;
        }
        assertThat(kept).isBetween(1_800, 2_200);
    }

    @Test
    void asyncTailDoesNotMakeATraceSlow() {
        TailSamplingPolicy policy = new TailSamplingPolicy(0.0);
        var d = policy.decide(List.of(
                ok("a", "", "order", "POST /orders", "SERVER", 0, 50),
                ok("c", "a", "notify", "process", "CONSUMER", 60, 2000)), k -> 500_000);
        assertThat(d.keep()).isFalse();
    }
}

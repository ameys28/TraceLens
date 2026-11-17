package com.tracelens.platform.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares a recent window (e.g. last 5 min) with a baseline window (e.g. the hour before)
 * for one service operation and reports error-rate spikes and latency regressions.
 */
public final class AnomalyDetector {

    public enum Type { ERROR_RATE, LATENCY }
    public enum Severity { WARNING, CRITICAL }

    public record WindowStats(long count, long errors, long p95Micros) {
        public double errorRate() { return count == 0 ? 0 : (double) errors / count; }
    }

    public record Thresholds(long minRequests, double minErrorRate, double errorRateMultiplier,
                             long minLatencyMicros, double latencyMultiplier) {
        public static Thresholds defaults() {
            return new Thresholds(10, 0.05, 3.0, 100_000, 2.0);
        }
    }

    public record Finding(Type type, Severity severity, double observed, double baseline, String message) {}

    private final Thresholds t;

    public AnomalyDetector(Thresholds thresholds) {
        this.t = thresholds;
    }

    public List<Finding> evaluate(String service, String operation, WindowStats recent, WindowStats baseline) {
        List<Finding> out = new ArrayList<>();
        if (recent.count() < t.minRequests()) return out;

        double rate = recent.errorRate();
        double baseRate = baseline.errorRate();
        double errorLimit = Math.max(t.minErrorRate(), baseRate * t.errorRateMultiplier());
        if (rate >= errorLimit) {
            Severity sev = rate >= 0.25 ? Severity.CRITICAL : Severity.WARNING;
            out.add(new Finding(Type.ERROR_RATE, sev, rate, baseRate, String.format(
                    "%s %s error rate %.1f%% (baseline %.1f%%, %d/%d requests failed)",
                    service, operation, rate * 100, baseRate * 100, recent.errors(), recent.count())));
        }

        if (baseline.count() >= t.minRequests() && baseline.p95Micros() > 0) {
            long limit = Math.max(t.minLatencyMicros(), (long) (baseline.p95Micros() * t.latencyMultiplier()));
            if (recent.p95Micros() >= limit) {
                double ratio = (double) recent.p95Micros() / baseline.p95Micros();
                Severity sev = ratio >= 4 ? Severity.CRITICAL : Severity.WARNING;
                out.add(new Finding(Type.LATENCY, sev, recent.p95Micros(), baseline.p95Micros(), String.format(
                        "%s %s p95 latency %d ms (baseline %d ms, %.1fx)",
                        service, operation, recent.p95Micros() / 1000, baseline.p95Micros() / 1000, ratio)));
            }
        }
        return out;
    }
}

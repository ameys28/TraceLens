package com.tracelens.demo.common;

import io.opentelemetry.api.trace.Span;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Runtime fault injection. Set through {@code POST /chaos} (the TraceLens UI proxies to it).
 * Lets you create latency spikes and error bursts and watch TraceLens detect them.
 */
@Component
public class ChaosState {
    private volatile long latencyMs = 0;
    private volatile double errorRate = 0.0;

    public void apply(String operation) {
        long latency = latencyMs;
        if (latency > 0) {
            long jitter = latency / 5 == 0 ? 0 : ThreadLocalRandom.current().nextLong(latency / 5);
            Span.current().setAttribute("chaos.injected_latency_ms", latency + jitter);
            Work.sleep((int) (latency + jitter), (int) (latency + jitter));
        }
        if (errorRate > 0 && Work.chance(errorRate)) {
            Span.current().setAttribute("chaos.injected_error", true);
            throw new ChaosException("Injected failure in " + operation);
        }
    }

    public synchronized void set(long latencyMs, double errorRate) {
        this.latencyMs = Math.max(0, latencyMs);
        this.errorRate = Math.min(1.0, Math.max(0.0, errorRate));
    }

    public long latencyMs() { return latencyMs; }
    public double errorRate() { return errorRate; }

    public static class ChaosException extends RuntimeException {
        public ChaosException(String message) { super(message); }
    }
}

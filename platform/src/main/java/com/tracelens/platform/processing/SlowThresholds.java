package com.tracelens.platform.processing;

import com.tracelens.platform.config.TraceLensProperties;
import com.tracelens.platform.core.LatencyHistogram;
import com.tracelens.platform.storage.RollupRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-operation "slow" threshold used by tail sampling: the p99 of the last hour,
 * learned from the rollups. Unknown operations fall back to a configured default.
 */
@Component
public class SlowThresholds {
    private static final Logger log = LoggerFactory.getLogger(SlowThresholds.class);
    private static final long MIN_SAMPLES = 50;

    private final RollupRepository rollups;
    private final long defaultMicros;
    private final long minMicros;
    private volatile Map<String, Long> thresholds = Map.of();

    public SlowThresholds(RollupRepository rollups, TraceLensProperties props) {
        this.rollups = rollups;
        this.defaultMicros = props.sampling().defaultSlowThresholdMs() * 1000;
        this.minMicros = props.sampling().minSlowThresholdMs() * 1000;
    }

    /** @param key {@code service|operation} */
    public long thresholdMicros(String key) {
        return thresholds.getOrDefault(key, defaultMicros);
    }

    public Map<String, Long> snapshot() {
        return thresholds;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void refresh() {
        try {
            Map<String, Long> next = new ConcurrentHashMap<>();
            rollups.bucketsByOperation(60).forEach((key, counts) -> {
                long total = 0;
                for (long c : counts) total += c;
                if (total >= MIN_SAMPLES) next.put(key, Math.max(minMicros, LatencyHistogram.percentile(counts, 0.99)));
            });
            thresholds = next;
        } catch (Exception e) {
            log.warn("could not refresh slow thresholds: {}", e.getMessage());
        }
    }
}

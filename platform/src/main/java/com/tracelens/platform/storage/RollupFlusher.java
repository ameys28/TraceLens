package com.tracelens.platform.storage;

import com.tracelens.platform.core.RollupAccumulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Flushes in-memory rollups to PostgreSQL every 5 seconds; on failure they are kept for the next try. */
@Component
public class RollupFlusher {
    private static final Logger log = LoggerFactory.getLogger(RollupFlusher.class);

    private final RollupAccumulator accumulator;
    private final RollupRepository repository;

    public RollupFlusher(RollupAccumulator accumulator, RollupRepository repository) {
        this.accumulator = accumulator;
        this.repository = repository;
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void flush() {
        RollupAccumulator.Snapshot snap = accumulator.drain();
        if (snap.isEmpty()) return;
        try {
            repository.write(snap);
        } catch (Exception e) {
            log.warn("rollup flush failed, will retry: {}", e.getMessage());
            accumulator.restore(snap);
        }
    }
}

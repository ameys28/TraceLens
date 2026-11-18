package com.tracelens.platform.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracelens.platform.config.TraceLensProperties;
import com.tracelens.platform.core.RollupAccumulator;
import com.tracelens.platform.core.SpanRecord;
import com.tracelens.platform.core.TailSamplingPolicy;
import com.tracelens.platform.core.TailSamplingPolicy.Decision;
import com.tracelens.platform.core.TailSamplingPolicy.Reason;
import com.tracelens.platform.storage.SpanWriter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Every second: claim traces that have been quiet for the decision window, update the
 * rollups with all of them, apply the tail-sampling policy and write the kept ones.
 */
@Component
public class TraceProcessor {
    private static final Logger log = LoggerFactory.getLogger(TraceProcessor.class);
    private static final int BATCH = 500;
    private static final int MAX_ROUNDS = 20;

    private final RedisTraceBuffer buffer;
    private final TailSamplingPolicy policy;
    private final SlowThresholds thresholds;
    private final RollupAccumulator rollups;
    private final SpanWriter writer;
    private final ObjectMapper mapper;
    private final MeterRegistry registry;
    private final long decisionWaitMs;

    public TraceProcessor(RedisTraceBuffer buffer, TailSamplingPolicy policy, SlowThresholds thresholds,
                          RollupAccumulator rollups, SpanWriter writer, ObjectMapper mapper,
                          MeterRegistry registry, TraceLensProperties props) {
        this.buffer = buffer;
        this.policy = policy;
        this.thresholds = thresholds;
        this.rollups = rollups;
        this.writer = writer;
        this.mapper = mapper;
        this.registry = registry;
        this.decisionWaitMs = props.sampling().decisionWaitMs();
        Gauge.builder("tracelens.buffer.pending_traces", buffer, RedisTraceBuffer::pendingTraces).register(registry);
    }

    @Scheduled(fixedDelayString = "${tracelens.sampling.poll-interval-ms:1000}", initialDelay = 3_000)
    public void processReadyTraces() {
        try {
            for (int round = 0; round < MAX_ROUNDS; round++) {
                List<RedisTraceBuffer.BufferedTrace> ready =
                        buffer.claimReady(System.currentTimeMillis() - decisionWaitMs, BATCH);
                if (ready.isEmpty()) return;
                process(ready);
                if (ready.size() < BATCH) return;
            }
        } catch (Exception e) {
            log.error("trace processing failed", e);
        }
    }

    void process(List<RedisTraceBuffer.BufferedTrace> ready) {
        Map<String, Boolean> earlier = buffer.decisions(ready.stream().map(RedisTraceBuffer.BufferedTrace::traceId).toList());
        Map<String, Boolean> newDecisions = new HashMap<>();

        for (RedisTraceBuffer.BufferedTrace t : ready) {
            List<SpanRecord> spans = parse(t.spanJson());
            if (spans.isEmpty()) continue;
            rollups.record(spans);

            Decision decision;
            boolean late = earlier.containsKey(t.traceId());
            if (late) {
                boolean kept = earlier.get(t.traceId());
                decision = new Decision(kept, kept ? Reason.LATE_KEPT : Reason.LATE_DROPPED);
            } else {
                decision = policy.decide(spans, thresholds::thresholdMicros);
                newDecisions.put(t.traceId(), decision.keep());
            }

            long minute = RollupAccumulator.minuteOf(spans.get(0).startNanos());
            rollups.recordSampling(minute, decision.reason().name(), spans.size());
            registry.counter("tracelens.sampling.traces", "reason", decision.reason().name()).increment();
            if (decision.keep()) writer.add(spans, decision.reason(), late);
        }

        buffer.saveDecisions(newDecisions);
        writer.flush();
    }

    private List<SpanRecord> parse(List<String> json) {
        List<SpanRecord> out = new ArrayList<>(json.size());
        Set<String> seen = new HashSet<>();
        for (String j : json) {
            try {
                SpanRecord s = mapper.readValue(j, SpanRecord.class);
                if (seen.add(s.spanId())) out.add(s); // at-least-once delivery from Kafka: de-duplicate
            } catch (Exception e) {
                log.debug("skipping unreadable span: {}", e.getMessage());
            }
        }
        return out;
    }
}

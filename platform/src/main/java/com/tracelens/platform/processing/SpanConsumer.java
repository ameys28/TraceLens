package com.tracelens.platform.processing;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;

/**
 * Consumes span batches from Kafka and appends them to the per-trace buffer in Redis.
 * Batch listener (spring.kafka.listener.type=batch): one Redis pipeline per poll.
 */
@Component
public class SpanConsumer {
    private final RedisTraceBuffer buffer;

    public SpanConsumer(RedisTraceBuffer buffer) {
        this.buffer = buffer;
    }

    @KafkaListener(topics = "${tracelens.span-topic}", groupId = "tracelens-processor")
    public void onSpans(List<ConsumerRecord<String, String>> records) {
        Map<String, List<String>> byTrace = new HashMap<>();
        for (ConsumerRecord<String, String> r : records) {
            if (r.key() == null || r.value() == null) continue;
            byTrace.computeIfAbsent(r.key(), k -> new java.util.ArrayList<>()).add(r.value());
        }
        if (!byTrace.isEmpty()) buffer.append(byTrace);
    }
}

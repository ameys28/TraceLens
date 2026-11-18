package com.tracelens.platform.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracelens.platform.config.TraceLensProperties;
import com.tracelens.platform.core.SpanRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** Publishes spans to Kafka keyed by trace ID. */
@Component
public class SpanPublisher {
    private static final Logger log = LoggerFactory.getLogger(SpanPublisher.class);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final String topic;
    private final Counter publishFailures;

    public SpanPublisher(KafkaTemplate<String, String> kafka, ObjectMapper mapper, TraceLensProperties props, MeterRegistry registry) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.topic = props.spanTopic();
        this.publishFailures = Counter.builder("tracelens.ingest.publish.failures").register(registry);
    }

    public void publish(List<SpanRecord> spans) {
        for (SpanRecord span : spans) {
            String json;
            try {
                json = mapper.writeValueAsString(span);
            } catch (JsonProcessingException e) {
                log.warn("could not serialize span {}", span.spanId(), e);
                continue;
            }
            kafka.send(topic, span.traceId(), json).whenComplete((result, ex) -> {
                if (ex != null) {
                    publishFailures.increment();
                    log.warn("failed to publish span to Kafka: {}", ex.getMessage());
                }
            });
        }
    }
}

package com.tracelens.platform.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.tracelens.platform.core.OtlpJsonParser;
import com.tracelens.platform.core.SpanRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * OTLP/HTTP trace receiver (JSON encoding) at the standard path {@code /v1/traces}.
 * Parsing is cheap and synchronous; everything else happens after Kafka, so a burst of
 * traffic is absorbed by the topic instead of hitting the database.
 */
@RestController
public class OtlpIngestController {
    private final SpanPublisher publisher;
    private final Counter spansReceived;
    private final Counter requests;

    public OtlpIngestController(SpanPublisher publisher, MeterRegistry registry) {
        this.publisher = publisher;
        this.spansReceived = Counter.builder("tracelens.ingest.spans").description("Spans received").register(registry);
        this.requests = Counter.builder("tracelens.ingest.requests").description("OTLP export requests").register(registry);
    }

    @PostMapping(path = "/v1/traces", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> export(@RequestBody JsonNode body) {
        requests.increment();
        List<SpanRecord> spans = OtlpJsonParser.parse(body);
        publisher.publish(spans);
        spansReceived.increment(spans.size());
        // OTLP success response: an ExportTraceServiceResponse with no partial-success info
        return ResponseEntity.ok(Map.of());
    }

    @PostMapping(path = "/v1/traces", consumes = "application/x-protobuf")
    public ResponseEntity<Map<String, Object>> protobufNotSupported() {
        return ResponseEntity.status(415).body(Map.of("error",
                "Send OTLP/HTTP JSON (encoding: json), or route through the OpenTelemetry Collector (see infra/otel-collector.yaml)"));
    }
}

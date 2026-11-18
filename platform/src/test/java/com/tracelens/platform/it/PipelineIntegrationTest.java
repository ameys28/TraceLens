package com.tracelens.platform.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end through real infrastructure: OTLP JSON -> ingest -> Kafka -> Redis buffer ->
 * tail sampling -> PostgreSQL -> query API. Skipped automatically when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "tracelens.sampling.decision-wait-ms=1000",
        "tracelens.sampling.poll-interval-ms=500",
        "tracelens.sampling.baseline-rate=0.0",
        "tracelens.alerting.enabled=false"
})
class PipelineIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    TestRestTemplate http;

    private static String otlp(String traceId, boolean error) {
        long start = System.currentTimeMillis() * 1_000_000L;
        return """
                {"resourceSpans":[
                  {"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"gateway"}}]},
                   "scopeSpans":[{"spans":[
                     {"traceId":"%1$s","spanId":"1000000000000001","name":"POST /api/checkout","kind":2,
                      "startTimeUnixNano":"%2$d","endTimeUnixNano":"%3$d","status":{}},
                     {"traceId":"%1$s","spanId":"1000000000000002","parentSpanId":"1000000000000001","name":"POST","kind":3,
                      "startTimeUnixNano":"%4$d","endTimeUnixNano":"%5$d","status":{}}]}]},
                  {"resource":{"attributes":[{"key":"service.name","value":{"stringValue":"payment-service"}}]},
                   "scopeSpans":[{"spans":[
                     {"traceId":"%1$s","spanId":"1000000000000003","parentSpanId":"1000000000000002","name":"POST /payments/charge","kind":2,
                      "startTimeUnixNano":"%6$d","endTimeUnixNano":"%7$d","status":%8$s,
                      "events":[{"name":"exception","timeUnixNano":"%7$d","attributes":[
                        {"key":"exception.type","value":{"stringValue":"java.lang.IllegalStateException"}},
                        {"key":"exception.message","value":{"stringValue":"card provider timeout"}}]}]}]}]}
                ]}
                """.formatted(traceId, start, start + 80_000_000L, start + 5_000_000L, start + 75_000_000L,
                start + 6_000_000L, start + 74_000_000L, error ? "{\"code\":2,\"message\":\"card provider timeout\"}" : "{}");
    }

    private ResponseEntity<Map> post(String body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("/v1/traces", new HttpEntity<>(body, h), Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorTraceIsKeptAssembledAndCounted() throws Exception {
        String errorTrace = "0af7651916cd43dd8448eb211c80319c";
        String okTrace = "0af7651916cd43dd8448eb211c80319d";
        assertThat(post(otlp(errorTrace, true)).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(post(otlp(okTrace, false)).getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<Map> trace = null;
        for (int i = 0; i < 60; i++) {
            trace = http.getForEntity("/api/traces/" + errorTrace, Map.class);
            if (trace.getStatusCode().is2xxSuccessful()) break;
            Thread.sleep(500);
        }
        assertThat(trace.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = trace.getBody();
        assertThat(body.get("samplingReason")).isEqualTo("ERROR");
        Map<String, Object> assembled = (Map<String, Object>) body.get("trace");
        assertThat(assembled.get("spanCount")).isEqualTo(3);
        Map<String, Object> rootCause = (Map<String, Object>) assembled.get("rootCause");
        assertThat(rootCause.get("serviceName")).isEqualTo("payment-service");
        assertThat(rootCause.get("message")).isEqualTo("card provider timeout");

        // healthy + fast trace with baseline-rate 0 is dropped ...
        assertThat(http.getForEntity("/api/traces/" + okTrace, Map.class).getStatusCode().value()).isEqualTo(404);

        // ... but still counted in the pre-sampling rollups (flushed every 5 s)
        List<Map<String, Object>> services = null;
        for (int i = 0; i < 30; i++) {
            services = http.getForObject("/api/services?windowMinutes=5", List.class);
            if (services != null && services.size() == 2) break;
            Thread.sleep(500);
        }
        assertThat(services).hasSize(2);
        Map<String, Object> deps = http.getForObject("/api/dependencies?windowMinutes=5", Map.class);
        assertThat((List<?>) deps.get("edges")).hasSize(1);
    }
}

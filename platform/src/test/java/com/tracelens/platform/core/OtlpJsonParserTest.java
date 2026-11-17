package com.tracelens.platform.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtlpJsonParserTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String PAYLOAD = """
            {"resourceSpans":[{
              "resource":{"attributes":[
                {"key":"service.name","value":{"stringValue":"order-service"}},
                {"key":"host.name","value":{"stringValue":"box-1"}},
                {"key":"process.command_args","value":{"arrayValue":{"values":[{"stringValue":"java"}]}}}]},
              "scopeSpans":[{"scope":{"name":"io.opentelemetry.spring-webmvc"},"spans":[
                {"traceId":"4BF92F3577B34DA6A3CE929D0E0E4736","spanId":"00f067aa0ba902b7","parentSpanId":"",
                 "name":"POST /orders","kind":2,
                 "startTimeUnixNano":"1700000000000000000","endTimeUnixNano":"1700000000250000000",
                 "attributes":[
                   {"key":"http.response.status_code","value":{"intValue":"500"}},
                   {"key":"retry","value":{"boolValue":true}},
                   {"key":"ratio","value":{"doubleValue":0.5}}],
                 "events":[{"name":"exception","timeUnixNano":"1700000000200000000","attributes":[
                   {"key":"exception.type","value":{"stringValue":"java.lang.IllegalStateException"}},
                   {"key":"exception.message","value":{"stringValue":"payment provider down"}}]}],
                 "status":{"code":2,"message":"payment provider down"}},
                {"traceId":"S/kvNXezTaajzpKdDg5HNg==","spanId":"AAAAAAAAAAE=","parentSpanId":"00f067aa0ba902b7",
                 "name":"SELECT","kind":"SPAN_KIND_CLIENT",
                 "startTimeUnixNano":1700000000010000000,"endTimeUnixNano":1700000000020000000,
                 "status":{}},
                {"traceId":"00000000000000000000000000000000","spanId":"00f067aa0ba902b8","name":"invalid"}
              ]}]
            }]}
            """;

    @Test
    void parsesSpansAttributesEventsAndStatus() throws Exception {
        List<SpanRecord> spans = OtlpJsonParser.parse(mapper.readTree(PAYLOAD));
        assertThat(spans).hasSize(2);

        SpanRecord server = spans.get(0);
        assertThat(server.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(server.serviceName()).isEqualTo("order-service");
        assertThat(server.kind()).isEqualTo("SERVER");
        assertThat(server.durationMicros()).isEqualTo(250_000L);
        assertThat(server.hasError()).isTrue();
        assertThat(server.rootSpan()).isTrue();
        assertThat(server.attributes().get("http.response.status_code")).isEqualTo(500L);
        assertThat(server.attributes().get("retry")).isEqualTo(true);
        assertThat(server.attributes().get("resource.host.name")).isEqualTo("box-1");
        assertThat(server.attributes().containsKey("resource.process.command_args")).isFalse();
        assertThat(server.events()).hasSize(1);
        assertThat(server.events().get(0).attributes().get("exception.message")).isEqualTo("payment provider down");
    }

    @Test
    void acceptsBase64IdsNumericTimesAndEnumNames() throws Exception {
        SpanRecord client = OtlpJsonParser.parse(mapper.readTree(PAYLOAD)).get(1);
        assertThat(client.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(client.spanId()).isEqualTo("0000000000000001");
        assertThat(client.kind()).isEqualTo("CLIENT");
        assertThat(client.statusCode()).isEqualTo("UNSET");
        assertThat(client.durationMicros()).isEqualTo(10_000L);
    }

    @Test
    void roundTripsThroughJson() throws Exception {
        SpanRecord server = OtlpJsonParser.parse(mapper.readTree(PAYLOAD)).get(0);
        SpanRecord back = mapper.readValue(mapper.writeValueAsString(server), SpanRecord.class);
        assertThat(back.spanId()).isEqualTo(server.spanId());
        assertThat(back.events()).hasSize(1);
        assertThat(back.startNanos()).isEqualTo(server.startNanos());
    }
}

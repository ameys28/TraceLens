package com.tracelens.platform.core;

import java.util.List;
import java.util.Map;

/** Small builder for test spans. Times are given in milliseconds from an arbitrary epoch. */
final class TestSpans {
    static final long BASE_NS = 1_700_000_000_000L * 1_000_000L;
    static final String TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    private TestSpans() {}

    static SpanRecord span(String id, String parent, String service, String name, String kind,
                           long startMs, long endMs, boolean error) {
        return new SpanRecord(TRACE, id, parent, service, name, kind,
                BASE_NS + startMs * 1_000_000L, BASE_NS + endMs * 1_000_000L,
                error ? "ERROR" : "UNSET", error ? "boom" : "", Map.of(), List.of());
    }

    static SpanRecord ok(String id, String parent, String service, String name, String kind, long startMs, long endMs) {
        return span(id, parent, service, name, kind, startMs, endMs, false);
    }
}

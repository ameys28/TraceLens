package com.tracelens.platform.core;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * Parses an OTLP/HTTP JSON {@code ExportTraceServiceRequest} into {@link SpanRecord}s.
 * <p>
 * Follows the OTLP JSON mapping: trace/span IDs are hex strings, 64-bit integers may be
 * strings, enums are integers. It is lenient where real exporters differ (base64 IDs,
 * enum names, numbers vs strings, the legacy {@code instrumentationLibrarySpans} key).
 */
public final class OtlpJsonParser {

    private static final String[] KINDS = {"UNSPECIFIED", "INTERNAL", "SERVER", "CLIENT", "PRODUCER", "CONSUMER"};
    private static final String[] STATUS = {"UNSET", "OK", "ERROR"};
    /** Resource attributes worth copying onto every span (everything else is noise for us). */
    private static final Set<String> KEPT_RESOURCE_ATTRS =
            Set.of("service.version", "service.instance.id", "host.name", "telemetry.sdk.language", "deployment.environment");

    private OtlpJsonParser() {}

    public static List<SpanRecord> parse(JsonNode root) {
        List<SpanRecord> out = new ArrayList<>();
        for (JsonNode rs : root.path("resourceSpans")) {
            Map<String, Object> resourceAttrs = attributes(rs.path("resource").path("attributes"));
            String service = String.valueOf(resourceAttrs.getOrDefault("service.name", "unknown-service"));
            Map<String, Object> keptResource = new LinkedHashMap<>();
            resourceAttrs.forEach((k, v) -> { if (KEPT_RESOURCE_ATTRS.contains(k)) keptResource.put("resource." + k, v); });

            JsonNode scopes = rs.has("scopeSpans") ? rs.path("scopeSpans") : rs.path("instrumentationLibrarySpans");
            for (JsonNode scope : scopes) {
                for (JsonNode s : scope.path("spans")) {
                    SpanRecord span = span(s, service, keptResource);
                    if (span != null) out.add(span);
                }
            }
        }
        return out;
    }

    private static SpanRecord span(JsonNode s, String service, Map<String, Object> resourceAttrs) {
        String traceId = id(s.path("traceId").asText(""), 32);
        String spanId = id(s.path("spanId").asText(""), 16);
        if (traceId.isEmpty() || spanId.isEmpty() || isAllZeros(traceId) || isAllZeros(spanId)) return null;
        String parent = id(s.path("parentSpanId").asText(""), 16);
        if (isAllZeros(parent)) parent = "";

        long start = asLong(s.path("startTimeUnixNano"));
        long end = asLong(s.path("endTimeUnixNano"));
        if (end < start) end = start;

        Map<String, Object> attrs = new LinkedHashMap<>(resourceAttrs);
        attrs.putAll(attributes(s.path("attributes")));

        List<SpanRecord.SpanEvent> events = new ArrayList<>();
        for (JsonNode e : s.path("events")) {
            events.add(new SpanRecord.SpanEvent(e.path("name").asText(""), asLong(e.path("timeUnixNano")), attributes(e.path("attributes"))));
        }

        JsonNode status = s.path("status");
        return new SpanRecord(
                traceId, spanId, parent, service,
                s.path("name").asText("unnamed"),
                enumValue(s.path("kind"), KINDS, "SPAN_KIND_"),
                start, end,
                enumValue(status.path("code"), STATUS, "STATUS_CODE_"),
                status.path("message").asText(""),
                attrs, events);
    }

    /** OTLP JSON uses hex; some exporters send base64. Normalizes to lowercase hex. */
    static String id(String raw, int hexLength) {
        if (raw == null || raw.isEmpty()) return "";
        if (raw.length() == hexLength && raw.chars().allMatch(OtlpJsonParser::isHex)) return raw.toLowerCase(Locale.ROOT);
        try {
            byte[] bytes = Base64.getDecoder().decode(raw);
            if (bytes.length * 2 != hexLength) return "";
            StringBuilder sb = new StringBuilder(hexLength);
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean isHex(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isAllZeros(String id) {
        if (id.isEmpty()) return false;
        for (int i = 0; i < id.length(); i++) if (id.charAt(i) != '0') return false;
        return true;
    }

    static long asLong(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return 0L;
        if (n.isNumber()) return n.asLong();
        try {
            return Long.parseLong(n.asText().trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String enumValue(JsonNode n, String[] names, String prefix) {
        if (n == null || n.isMissingNode() || n.isNull()) return names[0];
        if (n.isNumber()) {
            int i = n.asInt();
            return i >= 0 && i < names.length ? names[i] : names[0];
        }
        String text = n.asText();
        if (text.startsWith(prefix)) text = text.substring(prefix.length());
        for (String name : names) if (name.equalsIgnoreCase(text)) return name;
        try {
            int i = Integer.parseInt(text);
            return i >= 0 && i < names.length ? names[i] : names[0];
        } catch (NumberFormatException e) {
            return names[0];
        }
    }

    static Map<String, Object> attributes(JsonNode list) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (list == null || !list.isArray()) return out;
        for (JsonNode kv : list) {
            String key = kv.path("key").asText(null);
            if (key == null) continue;
            out.put(key, anyValue(kv.path("value")));
        }
        return out;
    }

    private static Object anyValue(JsonNode v) {
        if (v.has("stringValue")) return v.get("stringValue").asText();
        if (v.has("intValue")) return asLong(v.get("intValue"));
        if (v.has("doubleValue")) return v.get("doubleValue").asDouble();
        if (v.has("boolValue")) return v.get("boolValue").asBoolean();
        if (v.has("arrayValue")) {
            List<Object> items = new ArrayList<>();
            for (JsonNode item : v.path("arrayValue").path("values")) items.add(anyValue(item));
            return items;
        }
        if (v.has("kvlistValue")) return attributes(v.path("kvlistValue").path("values"));
        if (v.has("bytesValue")) return v.get("bytesValue").asText();
        return null;
    }
}

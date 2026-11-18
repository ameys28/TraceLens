package com.tracelens.platform.alerting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AlertRepository {

    public record Alert(long id, String alertKey, String type, String severity, String service, String operation,
                        String message, double observed, double baseline, String exampleTraceId, String topError,
                        String status, long openedAtMs, long lastSeenAtMs, Long resolvedAtMs) {}

    private static final String COLUMNS = """
            id, alert_key, type, severity, service, operation, message, observed, baseline, example_trace_id, top_error, status,
            EXTRACT(EPOCH FROM opened_at)::bigint * 1000 AS opened_ms,
            EXTRACT(EPOCH FROM last_seen_at)::bigint * 1000 AS seen_ms,
            (EXTRACT(EPOCH FROM resolved_at) * 1000)::bigint AS resolved_ms
            """;

    private final JdbcTemplate jdbc;

    public AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Alert> open() {
        return jdbc.query("SELECT " + COLUMNS + " FROM alerts WHERE status = 'OPEN'", this::map);
    }

    public List<Alert> list(String status, int limit) {
        if (status == null || status.equalsIgnoreCase("ALL")) {
            return jdbc.query("SELECT " + COLUMNS + " FROM alerts ORDER BY opened_at DESC LIMIT ?", this::map, limit);
        }
        return jdbc.query("SELECT " + COLUMNS + " FROM alerts WHERE status = ? ORDER BY opened_at DESC LIMIT ?",
                this::map, status.toUpperCase(), limit);
    }

    public Optional<Long> insertOpen(String key, String type, String severity, String service, String operation,
                                     String message, double observed, double baseline, String exampleTraceId, String topError) {
        List<Long> ids = jdbc.query("""
                INSERT INTO alerts (alert_key, type, severity, service, operation, message, observed, baseline,
                                    example_trace_id, top_error, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')
                ON CONFLICT (alert_key) WHERE status = 'OPEN' DO NOTHING
                RETURNING id
                """, (rs, i) -> rs.getLong(1),
                key, type, severity, service, operation, message, observed, baseline, exampleTraceId, topError);
        return ids.stream().findFirst();
    }

    public void touch(long id, String severity, String message, double observed) {
        jdbc.update("UPDATE alerts SET last_seen_at = now(), severity = ?, message = ?, observed = ? WHERE id = ?",
                severity, message, observed, id);
    }

    public void resolve(long id) {
        jdbc.update("UPDATE alerts SET status = 'RESOLVED', resolved_at = now() WHERE id = ? AND status = 'OPEN'", id);
    }

    private Alert map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long resolved = rs.getLong("resolved_ms");
        boolean resolvedNull = rs.wasNull();
        return new Alert(rs.getLong("id"), rs.getString("alert_key"), rs.getString("type"), rs.getString("severity"),
                rs.getString("service"), rs.getString("operation"), rs.getString("message"),
                rs.getDouble("observed"), rs.getDouble("baseline"), rs.getString("example_trace_id"),
                rs.getString("top_error"), rs.getString("status"), rs.getLong("opened_ms"), rs.getLong("seen_ms"),
                resolvedNull ? null : resolved);
    }
}

package com.tracelens.platform.alerting;

import com.tracelens.platform.config.TraceLensProperties;
import com.tracelens.platform.core.AnomalyDetector;
import com.tracelens.platform.query.QueryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Every 30 s compares the last 5 minutes of each operation with the hour before it.
 * Opens an alert (deduplicated by key) when a finding appears, enriches it with the most
 * common error message and an example trace, and resolves it once the finding is gone.
 */
@Component
@ConditionalOnProperty(name = "tracelens.alerting.enabled", havingValue = "true", matchIfMissing = true)
public class AlertEvaluator {
    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    private final QueryRepository query;
    private final AlertRepository alerts;
    private final AlertNotifier notifier;
    private final AnomalyDetector detector = new AnomalyDetector(AnomalyDetector.Thresholds.defaults());
    private final int recent;
    private final int baseline;

    public AlertEvaluator(QueryRepository query, AlertRepository alerts, AlertNotifier notifier, TraceLensProperties props) {
        this.query = query;
        this.alerts = alerts;
        this.notifier = notifier;
        this.recent = props.alerting().recentWindowMinutes();
        this.baseline = props.alerting().baselineWindowMinutes();
    }

    @Scheduled(fixedDelayString = "${tracelens.alerting.evaluation-interval-ms:30000}", initialDelay = 30_000)
    public void evaluate() {
        try {
            Map<String, AnomalyDetector.WindowStats> recentStats = query.windowStats(recent, 0);
            Map<String, AnomalyDetector.WindowStats> baseStats = query.windowStats(recent + baseline, recent);
            Map<String, AlertRepository.Alert> open = new HashMap<>();
            for (AlertRepository.Alert a : alerts.open()) open.put(a.alertKey(), a);

            Set<String> active = new HashSet<>();
            for (Map.Entry<String, AnomalyDetector.WindowStats> e : recentStats.entrySet()) {
                String[] parts = e.getKey().split("\\|", 2);
                String service = parts[0], operation = parts.length > 1 ? parts[1] : "";
                AnomalyDetector.WindowStats base = baseStats.getOrDefault(e.getKey(), new AnomalyDetector.WindowStats(0, 0, 0));
                for (AnomalyDetector.Finding f : detector.evaluate(service, operation, e.getValue(), base)) {
                    String key = f.type() + "|" + e.getKey();
                    active.add(key);
                    AlertRepository.Alert existing = open.get(key);
                    if (existing != null) {
                        alerts.touch(existing.id(), f.severity().name(), f.message(), f.observed());
                    } else {
                        open(key, service, operation, f);
                    }
                }
            }
            for (AlertRepository.Alert a : open.values()) {
                if (!active.contains(a.alertKey())) {
                    alerts.resolve(a.id());
                    notifier.send("RESOLVED: " + a.message());
                }
            }
        } catch (Exception e) {
            log.warn("alert evaluation failed: {}", e.getMessage());
        }
    }

    private void open(String key, String service, String operation, AnomalyDetector.Finding f) {
        String example = null, topError = null;
        if (f.type() == AnomalyDetector.Type.ERROR_RATE) {
            Optional<String[]> err = query.topError(service, recent);
            if (err.isPresent()) {
                topError = err.get()[0];
                example = err.get()[1];
            }
        } else {
            example = query.slowestTrace(service, operation, recent).orElse(null);
        }
        Optional<Long> id = alerts.insertOpen(key, f.type().name(), f.severity().name(), service, operation,
                f.message(), f.observed(), f.baseline(), example, topError);
        if (id.isPresent()) {
            notifier.send(f.severity() + ": " + f.message()
                    + (topError != null && !topError.isBlank() ? " | top error: " + topError : "")
                    + (example != null ? " | example trace: " + example : ""));
        }
    }
}

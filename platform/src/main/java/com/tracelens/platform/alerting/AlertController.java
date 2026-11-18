package com.tracelens.platform.alerting;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {
    private final AlertRepository alerts;
    private final ObjectProvider<AlertEvaluator> evaluator;

    public AlertController(AlertRepository alerts, ObjectProvider<AlertEvaluator> evaluator) {
        this.alerts = alerts;
        this.evaluator = evaluator;
    }

    @GetMapping
    public List<AlertRepository.Alert> list(@RequestParam(defaultValue = "ALL") String status,
                                            @RequestParam(defaultValue = "100") int limit) {
        return alerts.list(status, Math.max(1, Math.min(limit, 500)));
    }

    /** Runs the detector now instead of waiting for the next 30 s tick (handy in demos). */
    @PostMapping("/evaluate")
    public List<AlertRepository.Alert> evaluateNow() {
        evaluator.ifAvailable(AlertEvaluator::evaluate);
        return alerts.list("OPEN", 100);
    }
}

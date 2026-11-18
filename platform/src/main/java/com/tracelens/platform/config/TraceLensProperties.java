package com.tracelens.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "tracelens")
public record TraceLensProperties(
        String spanTopic,
        int retentionDays,
        int rollupRetentionDays,
        Sampling sampling,
        Alerting alerting,
        Map<String, String> chaosTargets) {

    public record Sampling(long decisionWaitMs, long pollIntervalMs, double baselineRate,
                           long defaultSlowThresholdMs, long minSlowThresholdMs) {}

    public record Alerting(boolean enabled, long evaluationIntervalMs, int recentWindowMinutes,
                           int baselineWindowMinutes, String webhookUrl) {}
}

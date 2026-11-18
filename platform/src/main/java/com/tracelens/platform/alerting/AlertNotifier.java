package com.tracelens.platform.alerting;

import com.tracelens.platform.config.TraceLensProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/** Sends alerts to a Slack-compatible incoming webhook ({"text": ...}); logs them if none is configured. */
@Component
public class AlertNotifier {
    private static final Logger log = LoggerFactory.getLogger(AlertNotifier.class);

    private final String webhookUrl;
    private final RestClient client;

    public AlertNotifier(TraceLensProperties props) {
        this.webhookUrl = props.alerting().webhookUrl();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public void send(String text) {
        log.warn("[ALERT] {}", text);
        if (webhookUrl == null || webhookUrl.isBlank()) return;
        try {
            client.post().uri(webhookUrl).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text)).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("alert webhook failed: {}", e.getMessage());
        }
    }
}

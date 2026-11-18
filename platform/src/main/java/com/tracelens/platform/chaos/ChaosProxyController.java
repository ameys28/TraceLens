package com.tracelens.platform.chaos;

import com.tracelens.platform.config.TraceLensProperties;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * Proxies fault-injection settings to the demo services, so the UI can create incidents
 * (latency, errors) and you can watch TraceLens detect them.
 */
@RestController
@RequestMapping("/api/chaos")
public class ChaosProxyController {
    private final Map<String, String> targets;
    private final RestClient client;

    public ChaosProxyController(TraceLensProperties props) {
        this.targets = props.chaosTargets() == null ? Map.of() : new TreeMap<>(props.chaosTargets());
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1_000);
        factory.setReadTimeout(2_000);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public record ChaosSettings(Long latencyMs, Double errorRate) {}

    @GetMapping
    public List<Map<String, Object>> all() {
        List<Map<String, Object>> out = new ArrayList<>();
        targets.forEach((service, url) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", service);
            try {
                Map<?, ?> state = client.get().uri(url + "/chaos").retrieve().body(Map.class);
                row.put("reachable", true);
                row.put("latencyMs", state == null ? 0 : state.get("latencyMs"));
                row.put("errorRate", state == null ? 0 : state.get("errorRate"));
            } catch (Exception e) {
                row.put("reachable", false);
            }
            out.add(row);
        });
        return out;
    }

    @PostMapping("/{service}")
    public ResponseEntity<?> set(@PathVariable String service, @RequestBody ChaosSettings settings) {
        String url = targets.get(service);
        if (url == null) return ResponseEntity.notFound().build();
        Map<?, ?> state = client.post().uri(url + "/chaos").contentType(MediaType.APPLICATION_JSON)
                .body(settings).retrieve().body(Map.class);
        return ResponseEntity.ok(state);
    }

    @DeleteMapping("/{service}")
    public ResponseEntity<?> reset(@PathVariable String service) {
        String url = targets.get(service);
        if (url == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(client.delete().uri(url + "/chaos").retrieve().body(Map.class));
    }

    @DeleteMapping
    public List<Map<String, Object>> resetAll() {
        targets.values().forEach(url -> {
            try {
                client.delete().uri(url + "/chaos").retrieve().toBodilessEntity();
            } catch (Exception ignored) {
                // service down: nothing to reset
            }
        });
        return all();
    }
}

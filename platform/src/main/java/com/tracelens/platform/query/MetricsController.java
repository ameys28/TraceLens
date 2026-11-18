package com.tracelens.platform.query;

import com.tracelens.platform.processing.RedisTraceBuffer;
import com.tracelens.platform.processing.SlowThresholds;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** RED metrics, dependency graph, sampling and storage stats. All come from pre-sampling rollups. */
@RestController
@RequestMapping("/api")
public class MetricsController {
    private final QueryRepository repo;
    private final RedisTraceBuffer buffer;
    private final SlowThresholds thresholds;

    public MetricsController(QueryRepository repo, RedisTraceBuffer buffer, SlowThresholds thresholds) {
        this.repo = repo;
        this.buffer = buffer;
        this.thresholds = thresholds;
    }

    private static int window(int minutes) {
        return Math.max(1, Math.min(minutes, 60 * 24 * 7));
    }

    @GetMapping("/services")
    public List<QueryRepository.ServiceStats> services(@RequestParam(defaultValue = "15") int windowMinutes) {
        return repo.serviceStats(window(windowMinutes));
    }

    @GetMapping("/services/{service}/operations")
    public List<QueryRepository.OperationStats> operations(@PathVariable String service,
                                                           @RequestParam(defaultValue = "15") int windowMinutes) {
        return repo.operationStats(service, window(windowMinutes));
    }

    @GetMapping("/operations")
    public List<QueryRepository.OperationStats> allOperations(@RequestParam(defaultValue = "15") int windowMinutes) {
        return repo.operationStats(null, window(windowMinutes));
    }

    @GetMapping("/services/{service}/timeseries")
    public List<QueryRepository.TimePoint> timeseries(@PathVariable String service,
                                                      @RequestParam(required = false) String operation,
                                                      @RequestParam(defaultValue = "60") int windowMinutes) {
        return repo.timeseries(service, operation == null || operation.isBlank() ? null : operation, window(windowMinutes));
    }

    /**
     * Service dependency graph: nodes carry the service's own RED stats, edges the
     * caller -> callee call volume, error rate and average latency.
     */
    @GetMapping("/dependencies")
    public Map<String, Object> dependencies(@RequestParam(defaultValue = "15") int windowMinutes) {
        int w = window(windowMinutes);
        List<QueryRepository.Edge> edges = repo.edges(w);
        Map<String, QueryRepository.ServiceStats> stats = new LinkedHashMap<>();
        for (QueryRepository.ServiceStats s : repo.serviceStats(w)) stats.put(s.service(), s);
        Set<String> names = new TreeSet<>(stats.keySet());
        for (QueryRepository.Edge e : edges) {
            names.add(e.caller());
            names.add(e.callee());
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String n : names) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", n);
            node.put("stats", stats.containsKey(n) ? stats.get(n).stats() : null);
            nodes.add(node);
        }
        return Map.of("nodes", nodes, "edges", edges);
    }

    @GetMapping("/sampling")
    public Map<String, Object> sampling(@RequestParam(defaultValue = "60") int windowMinutes) {
        int w = window(windowMinutes);
        List<QueryRepository.SamplingReasonStats> totals = repo.samplingTotals(w);
        long all = 0, kept = 0, allSpans = 0, keptSpans = 0;
        for (QueryRepository.SamplingReasonStats t : totals) {
            all += t.traces();
            allSpans += t.spans();
            if (!t.reason().equals("DROPPED") && !t.reason().equals("LATE_DROPPED")) {
                kept += t.traces();
                keptSpans += t.spans();
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", totals);
        out.put("series", repo.samplingSeries(w));
        out.put("tracesSeen", all);
        out.put("tracesKept", kept);
        out.put("spansSeen", allSpans);
        out.put("spansKept", keptSpans);
        out.put("storageReduction", allSpans == 0 ? 0 : 1.0 - (double) keptSpans / allSpans);
        out.put("pendingTraces", buffer.pendingTraces());
        out.put("learnedThresholds", thresholds.snapshot().size());
        out.put("partitions", repo.partitions());
        return out;
    }
}

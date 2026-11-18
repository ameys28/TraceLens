package com.tracelens.platform.query;

import com.tracelens.platform.core.SpanRecord;
import com.tracelens.platform.core.TraceAssembler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/traces")
public class TraceController {
    private final QueryRepository repo;

    public TraceController(QueryRepository repo) {
        this.repo = repo;
    }

    /** Search sampled traces. */
    @GetMapping
    public List<QueryRepository.TraceSummary> search(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long minDurationMs,
            @RequestParam(required = false) Long maxDurationMs,
            @RequestParam(defaultValue = "60") int lookbackMinutes,
            @RequestParam(defaultValue = "50") int limit) {
        return repo.searchTraces(service, operation, status, minDurationMs, maxDurationMs,
                Math.max(1, Math.min(lookbackMinutes, 60 * 24 * 30)), Math.max(1, Math.min(limit, 500)));
    }

    /** One trace, reassembled: tree, skew correction, self time, critical path, root cause. */
    @GetMapping("/{traceId}")
    public ResponseEntity<?> get(@PathVariable String traceId) {
        String id = traceId.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[0-9a-f]{32}")) {
            return ResponseEntity.badRequest().body(Map.of("error", "trace id must be 32 hex characters"));
        }
        List<SpanRecord> spans = repo.spansOfTrace(id);
        if (spans.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("error",
                    "trace not found (it may not have been sampled, or is still inside the decision window)"));
        }
        TraceAssembler.AssembledTrace trace = TraceAssembler.assemble(spans);
        return ResponseEntity.ok(Map.of(
                "trace", trace,
                "samplingReason", repo.samplingReason(id).orElse("UNKNOWN")));
    }
}

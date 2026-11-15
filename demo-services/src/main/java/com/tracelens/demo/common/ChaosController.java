package com.tracelens.demo.common;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/chaos")
public class ChaosController {
    private final ChaosState chaos;
    private final DemoProperties props;

    public ChaosController(ChaosState chaos, DemoProperties props) {
        this.chaos = chaos;
        this.props = props;
    }

    public record ChaosRequest(Long latencyMs, Double errorRate) {}

    @GetMapping
    public Map<String, Object> get() {
        return Map.of("role", props.role(), "latencyMs", chaos.latencyMs(), "errorRate", chaos.errorRate());
    }

    @PostMapping
    public Map<String, Object> set(@RequestBody ChaosRequest req) {
        chaos.set(req.latencyMs() == null ? 0 : req.latencyMs(), req.errorRate() == null ? 0 : req.errorRate());
        return get();
    }

    @DeleteMapping
    public Map<String, Object> reset() {
        chaos.set(0, 0);
        return get();
    }
}

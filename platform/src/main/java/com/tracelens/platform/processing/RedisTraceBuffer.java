package com.tracelens.platform.processing;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * Holds spans in Redis until a trace has been quiet for the decision window.
 * <pre>
 *   tl:trace:{traceId}     LIST  of span JSON
 *   tl:pending             ZSET  traceId -> last time a span arrived (ms)
 *   tl:decision:{traceId}  STRING K|D, remembered for 10 min so late spans follow the first decision
 * </pre>
 * Claiming ready traces is one Lua script (ZRANGEBYSCORE + ZREM + LRANGE + DEL), so several
 * platform instances can process in parallel without ever handling the same trace twice.
 */
@Component
public class RedisTraceBuffer {
    static final String PENDING = "tl:pending";
    static final String TRACE_PREFIX = "tl:trace:";
    static final String DECISION_PREFIX = "tl:decision:";
    private static final Duration TRACE_TTL = Duration.ofMinutes(10);
    private static final Duration DECISION_TTL = Duration.ofMinutes(10);

    static final String CLAIM_SCRIPT = """
            local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
            local out = {}
            for _, id in ipairs(ids) do
              redis.call('ZREM', KEYS[1], id)
              local key = ARGV[3] .. id
              local spans = redis.call('LRANGE', key, 0, -1)
              redis.call('DEL', key)
              table.insert(out, id)
              table.insert(out, spans)
            end
            return out
            """;

    public record BufferedTrace(String traceId, List<String> spanJson) {}

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> claimScript;

    public RedisTraceBuffer(StringRedisTemplate redis) {
        this.redis = redis;
        this.claimScript = new DefaultRedisScript<>(CLAIM_SCRIPT, List.class);
    }

    @SuppressWarnings("unchecked")
    public void append(Map<String, List<String>> spansByTrace) {
        String now = String.valueOf(System.currentTimeMillis());
        double score = Double.parseDouble(now);
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                spansByTrace.forEach((traceId, spans) -> {
                    String key = TRACE_PREFIX + traceId;
                    ops.opsForList().rightPushAll(key, spans);
                    ops.expire(key, TRACE_TTL);
                    ops.opsForZSet().add(PENDING, traceId, score);
                });
                return null;
            }
        });
    }

    /** Atomically takes up to {@code max} traces that have been quiet since {@code quietSinceMs}. */
    @SuppressWarnings("unchecked")
    public List<BufferedTrace> claimReady(long quietSinceMs, int max) {
        List<Object> raw = redis.execute(claimScript, List.of(PENDING),
                String.valueOf(quietSinceMs), String.valueOf(max), TRACE_PREFIX);
        if (raw == null || raw.isEmpty()) return List.of();
        List<BufferedTrace> out = new ArrayList<>(raw.size() / 2);
        for (int i = 0; i + 1 < raw.size(); i += 2) {
            String traceId = String.valueOf(raw.get(i));
            Object spans = raw.get(i + 1);
            List<String> json = new ArrayList<>();
            if (spans instanceof List<?> list) for (Object o : list) json.add(String.valueOf(o));
            if (!json.isEmpty()) out.add(new BufferedTrace(traceId, json));
        }
        return out;
    }

    /** Earlier decisions for these traces (for late-arriving spans): traceId -> true (kept) / false (dropped). */
    public Map<String, Boolean> decisions(Collection<String> traceIds) {
        if (traceIds.isEmpty()) return Map.of();
        List<String> ids = new ArrayList<>(traceIds);
        List<String> keys = ids.stream().map(id -> DECISION_PREFIX + id).toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        Map<String, Boolean> out = new HashMap<>();
        if (values == null) return out;
        for (int i = 0; i < ids.size(); i++) {
            String v = values.get(i);
            if (v != null) out.put(ids.get(i), "K".equals(v));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public void saveDecisions(Map<String, Boolean> decisions) {
        if (decisions.isEmpty()) return;
        redis.executePipelined(new SessionCallback<Object>() {
            @Override
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                RedisOperations<String, String> ops = (RedisOperations<String, String>) operations;
                decisions.forEach((id, keep) -> ops.opsForValue().set(DECISION_PREFIX + id, keep ? "K" : "D", DECISION_TTL));
                return null;
            }
        });
    }

    public long pendingTraces() {
        Long n = redis.opsForZSet().zCard(PENDING);
        return n == null ? 0 : n;
    }
}

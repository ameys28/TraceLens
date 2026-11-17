package com.tracelens.platform.core;

import java.util.*;

/**
 * Rebuilds a trace from its spans and analyses it:
 * <ul>
 *   <li><b>tree</b> – parent/child links; spans whose parent never arrived are marked orphans</li>
 *   <li><b>clock-skew correction</b> – a child on another host that appears to start before
 *       (or end after) its parent is shifted inside the parent, together with its subtree</li>
 *   <li><b>self time</b> – a span's duration minus the time covered by its children</li>
 *   <li><b>critical path</b> – the chain of work that actually determined end-to-end latency</li>
 *   <li><b>root-cause hint</b> – the deepest span that failed, with its exception</li>
 *   <li><b>bottleneck</b> – the critical-path span with the largest contribution</li>
 * </ul>
 */
public final class TraceAssembler {

    private TraceAssembler() {}

    public record AssembledSpan(
            String spanId, String parentSpanId, String serviceName, String name, String kind,
            long startOffsetMicros, long durationMicros, long selfTimeMicros, int depth,
            String statusCode, String statusMessage,
            Map<String, Object> attributes, List<SpanRecord.SpanEvent> events,
            boolean onCriticalPath, long criticalPathMicros, long skewAdjustmentMicros, boolean orphan) {}

    public record RootCause(String spanId, String serviceName, String operation, String exceptionType, String message) {}

    public record Bottleneck(String spanId, String serviceName, String operation, long criticalPathMicros, double shareOfTrace) {}

    public record AssembledTrace(
            String traceId, long startEpochMicros, long durationMicros, int spanCount, int errorCount,
            String rootService, String rootOperation, List<String> services,
            List<AssembledSpan> spans, RootCause rootCause, Bottleneck bottleneck) {}

    /** Mutable working node. Times are in microseconds, already skew-adjusted. */
    private static final class Node {
        final SpanRecord span;
        final List<Node> children = new ArrayList<>();
        long start, end, skew, self, critical;
        int depth;
        boolean orphan;

        Node(SpanRecord span) {
            this.span = span;
            this.start = span.startNanos() / 1_000;
            this.end = span.endNanos() / 1_000;
        }

        long duration() { return Math.max(0, end - start); }
    }

    public static AssembledTrace assemble(List<SpanRecord> input) {
        if (input == null || input.isEmpty()) throw new IllegalArgumentException("trace has no spans");

        // 1. de-duplicate and index
        Map<String, Node> byId = new LinkedHashMap<>();
        for (SpanRecord s : input) byId.putIfAbsent(s.spanId(), new Node(s));

        // 2. link children; collect roots (no parent, or parent missing = orphan)
        List<Node> roots = new ArrayList<>();
        for (Node n : byId.values()) {
            String parentId = n.span.parentSpanId();
            Node parent = parentId == null || parentId.isEmpty() ? null : byId.get(parentId);
            if (parent != null && parent != n) {
                parent.children.add(n);
            } else {
                n.orphan = parentId != null && !parentId.isEmpty();
                roots.add(n);
            }
        }
        // a cycle (corrupt data) leaves nodes unreachable from any root: treat them as orphans
        Set<Node> reachable = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node r : roots) collect(r, reachable);
        for (Node n : byId.values()) {
            if (!reachable.contains(n)) {
                n.orphan = true;
                n.children.clear();
                roots.add(n);
                reachable.add(n);
            }
        }

        Comparator<Node> byStart = Comparator.comparingLong((Node n) -> n.start).thenComparing(n -> n.span.spanId());
        roots.sort(Comparator.<Node, Boolean>comparing(n -> n.orphan).thenComparing(byStart));
        Node primary = roots.get(0);

        // 3. skew correction + depth, top-down
        for (Node r : roots) adjust(r, 0, 0, byStart);

        // 4. self time
        for (Node n : byId.values()) n.self = selfTime(n);

        // 5. critical path from the primary root
        criticalPath(primary, primary.end);

        // 6. flatten in display (DFS, start-time) order
        long traceStart = Long.MAX_VALUE, traceEnd = Long.MIN_VALUE;
        for (Node n : byId.values()) {
            traceStart = Math.min(traceStart, n.start);
            traceEnd = Math.max(traceEnd, n.end);
        }
        List<AssembledSpan> flat = new ArrayList<>(byId.size());
        Deque<Node> stack = new ArrayDeque<>();
        for (int i = roots.size() - 1; i >= 0; i--) stack.push(roots.get(i));
        Set<String> services = new LinkedHashSet<>();
        int errors = 0;
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            services.add(n.span.serviceName());
            if (n.span.hasError()) errors++;
            flat.add(new AssembledSpan(
                    n.span.spanId(), n.span.parentSpanId(), n.span.serviceName(), n.span.name(), n.span.kind(),
                    n.start - traceStart, n.duration(), n.self, n.depth,
                    n.span.statusCode(), n.span.statusMessage(),
                    n.span.attributes() == null ? Map.of() : n.span.attributes(),
                    n.span.events() == null ? List.of() : n.span.events(),
                    n.critical > 0, n.critical, n.skew, n.orphan));
            for (int i = n.children.size() - 1; i >= 0; i--) stack.push(n.children.get(i));
        }

        long totalDuration = Math.max(0, traceEnd - traceStart);
        return new AssembledTrace(
                primary.span.traceId(), traceStart, totalDuration, flat.size(), errors,
                primary.span.serviceName(), primary.span.name(), List.copyOf(services),
                flat, rootCause(byId.values()), bottleneck(byId.values(), primary.duration()));
    }

    private static void collect(Node n, Set<Node> seen) {
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(n);
        while (!stack.isEmpty()) {
            Node cur = stack.pop();
            if (!seen.add(cur)) continue;
            for (Node c : cur.children) stack.push(c);
        }
    }

    /**
     * Shifts {@code n} by the delta inherited from its ancestors, then, if it is a cross-service
     * child that does not fit inside its parent, centres it inside the parent (Jaeger's approach)
     * and passes that extra shift on to its own subtree.
     */
    private static void adjust(Node root, long inheritedDelta, int depth, Comparator<Node> byStart) {
        record Item(Node node, Node parent, long delta, int depth) {}
        Deque<Item> stack = new ArrayDeque<>();
        stack.push(new Item(root, null, inheritedDelta, depth));
        while (!stack.isEmpty()) {
            Item it = stack.pop();
            Node n = it.node();
            n.start += it.delta();
            n.end += it.delta();
            n.skew = it.delta();
            n.depth = it.depth();
            Node p = it.parent();
            // Only synchronous RPC hops (CLIENT -> SERVER) must nest inside the parent.
            // Async hops (PRODUCER -> CONSUMER) legitimately start after the parent ends.
            boolean syncRpc = p != null && "CLIENT".equals(p.span.kind()) && "SERVER".equals(n.span.kind());
            if (syncRpc && !p.span.serviceName().equals(n.span.serviceName())
                    && (n.start < p.start || n.end > p.end)) {
                long extra;
                if (n.duration() <= p.duration()) {
                    long latency = (p.duration() - n.duration()) / 2;
                    extra = (p.start + latency) - n.start;
                } else {
                    extra = p.start - n.start; // child longer than parent: at least align the starts
                }
                n.start += extra;
                n.end += extra;
                n.skew += extra;
            }
            n.children.sort(byStart);
            for (int i = n.children.size() - 1; i >= 0; i--) {
                stack.push(new Item(n.children.get(i), n, n.skew, it.depth() + 1));
            }
        }
    }

    /** Duration minus the union of child intervals clipped to the span. */
    private static long selfTime(Node n) {
        if (n.children.isEmpty()) return n.duration();
        List<long[]> intervals = new ArrayList<>();
        for (Node c : n.children) {
            long s = Math.max(c.start, n.start), e = Math.min(c.end, n.end);
            if (e > s) intervals.add(new long[]{s, e});
        }
        intervals.sort(Comparator.comparingLong(a -> a[0]));
        long covered = 0, curS = -1, curE = -1;
        for (long[] iv : intervals) {
            if (curE < 0 || iv[0] > curE) {
                if (curE >= 0) covered += curE - curS;
                curS = iv[0];
                curE = iv[1];
            } else {
                curE = Math.max(curE, iv[1]);
            }
        }
        if (curE >= 0) covered += curE - curS;
        return Math.max(0, n.duration() - covered);
    }

    /**
     * Walks backwards from the end of the span: the last child to finish before the cursor
     * is on the critical path; the gaps between such children are the span's own time.
     */
    private static void criticalPath(Node root, long rootCursor) {
        record Frame(Node node, long cursorEnd) {}
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(root, rootCursor));
        while (!stack.isEmpty()) {
            Frame f = stack.pop();
            Node n = f.node();
            long cursor = Math.min(n.end, f.cursorEnd());
            List<Node> children = new ArrayList<>(n.children);
            children.sort(Comparator.comparingLong((Node c) -> c.end).reversed());
            for (Node c : children) {
                if (cursor <= n.start) break;
                if (c.start >= cursor || c.end <= n.start) continue;
                long childEnd = Math.min(c.end, cursor);
                n.critical += cursor - childEnd;        // gap after the child = parent's own work
                stack.push(new Frame(c, childEnd));
                cursor = Math.max(c.start, n.start);
            }
            if (cursor > n.start) n.critical += cursor - n.start;
        }
    }

    private static RootCause rootCause(Collection<Node> nodes) {
        Node best = null;
        for (Node n : nodes) {
            if (!n.span.hasError()) continue;
            if (best == null || n.depth > best.depth || (n.depth == best.depth && n.start < best.start)) best = n;
        }
        if (best == null) return null;
        String type = null;
        String message = best.span.statusMessage();
        if (best.span.events() != null) {
            for (SpanRecord.SpanEvent e : best.span.events()) {
                if ("exception".equals(e.name()) && e.attributes() != null) {
                    Object t = e.attributes().get("exception.type");
                    Object m = e.attributes().get("exception.message");
                    if (t != null) type = String.valueOf(t);
                    if (m != null) message = String.valueOf(m);
                    break;
                }
            }
        }
        return new RootCause(best.span.spanId(), best.span.serviceName(), best.span.name(), type, message);
    }

    private static Bottleneck bottleneck(Collection<Node> nodes, long rootDuration) {
        Node best = null;
        for (Node n : nodes) if (n.critical > 0 && (best == null || n.critical > best.critical)) best = n;
        if (best == null) return null;
        double share = rootDuration == 0 ? 0 : Math.min(1.0, (double) best.critical / rootDuration);
        return new Bottleneck(best.span.spanId(), best.span.serviceName(), best.span.name(), best.critical, share);
    }
}

package com.tracelens.demo.common;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Business correlation ID, separate from the trace ID.
 * <ol>
 *   <li>Taken from the {@code X-Correlation-Id} header (edge), or</li>
 *   <li>from W3C baggage {@code correlation.id} (internal hops), or</li>
 *   <li>generated.</li>
 * </ol>
 * It is put in baggage (so the agent forwards it on every HTTP and Kafka hop), in the
 * logging MDC (so every log line carries it next to trace_id/span_id), and on the span.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    public static final String BAGGAGE_KEY = "correlation.id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(HEADER);
        if (id == null || id.isBlank()) id = Baggage.current().getEntryValue(BAGGAGE_KEY);
        if (id == null || id.isBlank()) id = UUID.randomUUID().toString();

        Span.current().setAttribute(BAGGAGE_KEY, id);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try (Scope ignored = Baggage.current().toBuilder().put(BAGGAGE_KEY, id).build().makeCurrent()) {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }
}

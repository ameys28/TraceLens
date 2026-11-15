package com.tracelens.demo.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tracelens.demo.common.ChaosState;
import com.tracelens.demo.common.CorrelationIdFilter;
import com.tracelens.demo.common.Dtos.OrderPlacedEvent;
import com.tracelens.demo.common.Work;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Async consumer. The agent extracts the trace context (and baggage) from the Kafka
 * record headers, so this CONSUMER span joins the original checkout trace.
 */
@Component
@ConditionalOnProperty(name = "demo.role", havingValue = "notification")
public class NotificationListener {
    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final ObjectMapper mapper;
    private final ChaosState chaos;

    public NotificationListener(ObjectMapper mapper, ChaosState chaos) {
        this.mapper = mapper;
        this.chaos = chaos;
    }

    @KafkaListener(topics = "order-events", groupId = "notification-service")
    public void onOrderPlaced(String payload) throws Exception {
        OrderPlacedEvent event = mapper.readValue(payload, OrderPlacedEvent.class);
        String correlationId = Baggage.current().getEntryValue(CorrelationIdFilter.BAGGAGE_KEY);
        if (correlationId == null) correlationId = event.correlationId();
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        try {
            Span.current().setAttribute("app.order_id", event.orderId());
            if (correlationId != null) Span.current().setAttribute(CorrelationIdFilter.BAGGAGE_KEY, correlationId);
            chaos.apply("notify");
            String body = render(event);
            send(event.customerId(), body);
            log.info("notification sent for order {}", event.orderId());
        } catch (ChaosState.ChaosException e) {
            Span.current().recordException(e);
            Span.current().setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getMessage());
            log.error("notification failed for order {}", event.orderId());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    @WithSpan("notification.render")
    String render(OrderPlacedEvent event) {
        Work.sleep(2, 8);
        return "Order " + event.orderId() + " confirmed, total " + event.amount();
    }

    @WithSpan("notification.send")
    void send(String customerId, String body) {
        Span.current().setAttribute("peer.service", "email-provider");
        Work.sleep(15, 45);
    }
}

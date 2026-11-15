package com.tracelens.demo.order;

import com.tracelens.demo.common.ChaosState;
import com.tracelens.demo.common.CorrelationIdFilter;
import com.tracelens.demo.common.Downstream;
import com.tracelens.demo.common.Dtos.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;

import java.util.UUID;

/**
 * Orchestrates an order: price -> reserve stock -> charge payment -> publish event.
 * The Kafka publish is the async hop: trace context travels in Kafka record headers.
 */
@RestController
@RequestMapping("/orders")
@ConditionalOnProperty(name = "demo.role", havingValue = "order")
public class OrderController {
    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final Downstream downstream;
    private final ChaosState chaos;
    private final PricingService pricing;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public OrderController(Downstream downstream, ChaosState chaos, PricingService pricing,
                           KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
        this.downstream = downstream;
        this.chaos = chaos;
        this.pricing = pricing;
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @PostMapping
    public OrderResponse create(@RequestBody OrderRequest req) throws JsonProcessingException {
        chaos.apply("create-order");
        String orderId = "ord-" + UUID.randomUUID().toString().substring(0, 8);
        Span.current().setAttribute("app.order_id", orderId);

        double total = pricing.price(req);

        downstream.inventory().post().uri("/inventory/reserve")
                .body(new ReserveRequest(orderId, req.items()))
                .retrieve().toBodilessEntity();

        try {
            downstream.payment().post().uri("/payments/charge")
                    .body(new ChargeRequest(orderId, req.customerId(), total))
                    .retrieve().body(ChargeResponse.class);
        } catch (HttpClientErrorException e) {
            // Compensating action: give the stock back.
            log.warn("payment rejected for {}, releasing stock", orderId);
            downstream.inventory().post().uri("/inventory/release")
                    .body(new ReserveRequest(orderId, req.items()))
                    .retrieve().toBodilessEntity();
            throw e;
        }

        String correlationId = Baggage.current().getEntryValue(CorrelationIdFilter.BAGGAGE_KEY);
        String event = mapper.writeValueAsString(new OrderPlacedEvent(orderId, req.customerId(), total, correlationId));
        kafka.send(OrderTopics.ORDER_EVENTS, orderId, event);
        log.info("order {} placed, total {}", orderId, total);
        return new OrderResponse(orderId, "PLACED", total);
    }
}

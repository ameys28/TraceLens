package com.tracelens.demo.gateway;

import com.tracelens.demo.common.ChaosState;
import com.tracelens.demo.common.Downstream;
import com.tracelens.demo.common.Dtos.CheckoutRequest;
import com.tracelens.demo.common.Dtos.OrderRequest;
import com.tracelens.demo.common.Dtos.OrderResponse;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Edge service: the entry point of every traced request. */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(name = "demo.role", havingValue = "gateway")
public class GatewayController {
    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final Downstream downstream;
    private final ChaosState chaos;
    private final CartValidator validator;

    public GatewayController(Downstream downstream, ChaosState chaos, CartValidator validator) {
        this.downstream = downstream;
        this.chaos = chaos;
        this.validator = validator;
    }

    @PostMapping("/checkout")
    public OrderResponse checkout(@RequestBody CheckoutRequest req) {
        chaos.apply("checkout");
        validator.validate(req);
        double amount = req.amount() != null ? req.amount() : ThreadLocalRandom.current().nextDouble(100, 4000);
        Span.current().setAttribute("app.customer_id", String.valueOf(req.customerId()));
        Span.current().setAttribute("app.order_amount", amount);
        log.info("checkout started for customer {}", req.customerId());

        OrderResponse resp = downstream.order().post()
                .uri("/orders")
                .body(new OrderRequest(req.customerId(), req.items(), amount))
                .retrieve()
                .body(OrderResponse.class);

        log.info("checkout finished, order {}", resp == null ? null : resp.orderId());
        return resp;
    }

    @GetMapping("/products/{sku}")
    public Map<?, ?> product(@PathVariable String sku) {
        chaos.apply("product");
        return downstream.inventory().get().uri("/inventory/{sku}", sku).retrieve().body(Map.class);
    }
}

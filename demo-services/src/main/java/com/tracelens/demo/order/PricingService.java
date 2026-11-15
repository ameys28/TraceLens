package com.tracelens.demo.order;

import com.tracelens.demo.common.Dtos.OrderRequest;
import com.tracelens.demo.common.Work;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "demo.role", havingValue = "order")
public class PricingService {
    @WithSpan("order.price")
    public double price(OrderRequest req) {
        Work.sleep(3, 12);
        double tax = Math.round(req.amount() * 0.18 * 100) / 100.0;
        Span.current().setAttribute("app.tax", tax);
        return req.amount() + tax;
    }
}

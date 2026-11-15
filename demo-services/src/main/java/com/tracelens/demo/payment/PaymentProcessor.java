package com.tracelens.demo.payment;

import com.tracelens.demo.common.Dtos.ChargeRequest;
import com.tracelens.demo.common.Work;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "demo.role", havingValue = "payment")
public class PaymentProcessor {

    /** ~2% of fraud checks are naturally slow, which gives the latency distribution a real tail. */
    @WithSpan("fraud.check")
    public void fraudCheck(ChargeRequest req) {
        boolean slow = Work.chance(0.02);
        Span.current().setAttribute("fraud.deep_scan", slow);
        if (slow) Work.sleep(600, 900);
        else Work.sleep(8, 30);
    }

    @WithSpan("card.provider.charge")
    public void chargeCard(ChargeRequest req) {
        Span.current().setAttribute("peer.service", "card-provider");
        Work.sleep(25, 70);
    }
}

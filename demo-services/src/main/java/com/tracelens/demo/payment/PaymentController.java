package com.tracelens.demo.payment;

import com.tracelens.demo.common.ApiErrorHandler.BusinessException;
import com.tracelens.demo.common.ChaosState;
import com.tracelens.demo.common.Dtos.ChargeRequest;
import com.tracelens.demo.common.Dtos.ChargeResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/payments")
@ConditionalOnProperty(name = "demo.role", havingValue = "payment")
public class PaymentController {
    private final ChaosState chaos;
    private final PaymentProcessor processor;

    public PaymentController(ChaosState chaos, PaymentProcessor processor) {
        this.chaos = chaos;
        this.processor = processor;
    }

    @PostMapping("/charge")
    public ChargeResponse charge(@RequestBody ChargeRequest req) {
        chaos.apply("charge");
        processor.fraudCheck(req);
        if (req.amount() > 10_000) {
            throw new BusinessException(HttpStatus.PAYMENT_REQUIRED, "payment declined: amount over limit");
        }
        processor.chargeCard(req);
        return new ChargeResponse("pay-" + UUID.randomUUID().toString().substring(0, 8), "CAPTURED");
    }
}

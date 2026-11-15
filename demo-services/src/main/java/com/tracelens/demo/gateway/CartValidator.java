package com.tracelens.demo.gateway;

import com.tracelens.demo.common.ApiErrorHandler.BusinessException;
import com.tracelens.demo.common.Dtos.CheckoutRequest;
import com.tracelens.demo.common.Work;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CartValidator {
    /** Manual INTERNAL span: shows up as a child of the gateway's server span. */
    @WithSpan("cart.validate")
    public void validate(CheckoutRequest req) {
        Work.sleep(1, 5);
        if (req.items() == null || req.items().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "cart is empty");
        }
    }
}

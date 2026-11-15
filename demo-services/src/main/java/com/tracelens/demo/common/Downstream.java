package com.tracelens.demo.common;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP clients for downstream services. The OpenTelemetry agent instruments the
 * underlying JDK HttpClient, so every call gets a CLIENT span and a W3C
 * {@code traceparent} + {@code baggage} header automatically.
 */
@Component
public class Downstream {
    private final RestClient order;
    private final RestClient inventory;
    private final RestClient payment;

    public Downstream(RestClient.Builder builder, DemoProperties props) {
        this.order = builder.clone().baseUrl(props.urls().order()).build();
        this.inventory = builder.clone().baseUrl(props.urls().inventory()).build();
        this.payment = builder.clone().baseUrl(props.urls().payment()).build();
    }

    public RestClient order() { return order; }
    public RestClient inventory() { return inventory; }
    public RestClient payment() { return payment; }
}

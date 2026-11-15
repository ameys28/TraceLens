package com.tracelens.demo.common;

import java.util.List;

public final class Dtos {
    private Dtos() {}

    public record Item(String sku, int qty) {}

    public record CheckoutRequest(String customerId, List<Item> items, Double amount) {}

    public record OrderRequest(String customerId, List<Item> items, double amount) {}

    public record OrderResponse(String orderId, String status, double amount) {}

    public record ReserveRequest(String orderId, List<Item> items) {}

    public record ChargeRequest(String orderId, String customerId, double amount) {}

    public record ChargeResponse(String paymentId, String status) {}

    public record OrderPlacedEvent(String orderId, String customerId, double amount, String correlationId) {}
}

package com.tracelens.demo.inventory;

import com.tracelens.demo.common.ChaosState;
import com.tracelens.demo.common.Dtos.Item;
import com.tracelens.demo.common.Dtos.ReserveRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/inventory")
@ConditionalOnProperty(name = "demo.role", havingValue = "inventory")
public class InventoryController {
    private final ChaosState chaos;
    private final StockRepository stock;

    public InventoryController(ChaosState chaos, StockRepository stock) {
        this.chaos = chaos;
        this.stock = stock;
    }

    @GetMapping("/{sku}")
    public Map<String, Object> get(@PathVariable String sku) {
        chaos.apply("get-stock");
        return Map.of("sku", sku, "available", stock.lookup(sku));
    }

    @PostMapping("/reserve")
    public Map<String, Object> reserve(@RequestBody ReserveRequest req) {
        chaos.apply("reserve");
        for (Item item : req.items()) stock.reserve(item.sku(), item.qty());
        return Map.of("orderId", req.orderId(), "reserved", true);
    }

    @PostMapping("/release")
    public Map<String, Object> release(@RequestBody ReserveRequest req) {
        for (Item item : req.items()) stock.release(item.sku(), item.qty());
        return Map.of("orderId", req.orderId(), "released", true);
    }
}

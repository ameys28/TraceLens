package com.tracelens.demo.inventory;

import com.tracelens.demo.common.ApiErrorHandler.BusinessException;
import com.tracelens.demo.common.Work;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.SpanAttribute;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

/** Pretend database. Each method is a manual span that looks like a DB call. */
@Repository
@ConditionalOnProperty(name = "demo.role", havingValue = "inventory")
public class StockRepository {

    @WithSpan("SELECT stock")
    public int lookup(@SpanAttribute("app.sku") String sku) {
        dbAttrs("SELECT available FROM stock WHERE sku = ?");
        Work.sleep(2, 15);
        return "SOLD-OUT".equals(sku) ? 0 : 100;
    }

    @WithSpan("UPDATE stock reserve")
    public void reserve(@SpanAttribute("app.sku") String sku, @SpanAttribute("app.qty") int qty) {
        dbAttrs("UPDATE stock SET available = available - ? WHERE sku = ? AND available >= ?");
        Work.sleep(5, 25);
        if ("SOLD-OUT".equals(sku)) {
            throw new BusinessException(HttpStatus.CONFLICT, "out of stock: " + sku);
        }
    }

    @WithSpan("UPDATE stock release")
    public void release(@SpanAttribute("app.sku") String sku, @SpanAttribute("app.qty") int qty) {
        dbAttrs("UPDATE stock SET available = available + ? WHERE sku = ?");
        Work.sleep(3, 10);
    }

    private static void dbAttrs(String statement) {
        Span.current().setAttribute("db.system", "postgresql");
        Span.current().setAttribute("db.query.text", statement);
    }
}

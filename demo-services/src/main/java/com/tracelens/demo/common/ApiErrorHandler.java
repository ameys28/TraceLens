package com.tracelens.demo.common;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Map;

/**
 * Maps failures to HTTP responses and records them on the current span so the
 * exception (type, message, stack trace) shows up in TraceLens.
 */
@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    /** Business errors that are expected (sold out, declined): 4xx, not a server error. */
    public static class BusinessException extends RuntimeException {
        private final HttpStatus status;
        public BusinessException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
        public HttpStatus status() { return status; }
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> business(BusinessException e) {
        Span.current().setAttribute("business.error", e.getMessage());
        log.info("business rule rejected request: {}", e.getMessage());
        return ResponseEntity.status(e.status()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ChaosState.ChaosException.class)
    public ResponseEntity<Map<String, Object>> chaos(ChaosState.ChaosException e) {
        markError(e);
        log.error("chaos failure: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    /** A downstream service answered with an error status. 4xx passes through, 5xx becomes 502. */
    @ExceptionHandler(HttpStatusCodeException.class)
    public ResponseEntity<Map<String, Object>> downstream(HttpStatusCodeException e) {
        if (e.getStatusCode().is4xxClientError()) {
            Span.current().setAttribute("business.error", "downstream " + e.getStatusCode().value());
            return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", "downstream rejected: " + e.getStatusCode().value()));
        }
        markError(e);
        log.error("downstream failure: {}", e.getStatusCode());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "downstream failure: " + e.getStatusCode().value()));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, Object>> unreachable(ResourceAccessException e) {
        markError(e);
        log.error("downstream unreachable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "downstream unreachable"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        markError(e);
        log.error("unexpected failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    private static void markError(Throwable e) {
        Span span = Span.current();
        span.recordException(e);
        span.setStatus(StatusCode.ERROR, String.valueOf(e.getMessage()));
    }
}

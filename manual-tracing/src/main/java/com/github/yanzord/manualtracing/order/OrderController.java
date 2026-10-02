package com.github.yanzord.manualtracing.order;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService service;
    private final Tracer tracer;

    public OrderController(OrderService service, Tracer tracer) {
        this.service = service;
        this.tracer = tracer;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Order createOrder() {
        Span span = tracer.spanBuilder("POST /orders").setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope scope = span.makeCurrent()) {
            return service.createOrder();
        } finally {
            span.end();
        }
    }

}

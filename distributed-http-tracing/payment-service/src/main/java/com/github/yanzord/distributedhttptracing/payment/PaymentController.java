package com.github.yanzord.distributedhttptracing.payment;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

@RestController
public class PaymentController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);

    private static final TextMapGetter<HttpServletRequest> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return Collections.list(carrier.getHeaderNames());
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            return carrier == null ? null : carrier.getHeader(key);
        }
    };

    private final Tracer tracer;

    public PaymentController(Tracer tracer) {
        this.tracer = tracer;
    }

    @PostMapping("/payments")
    public String processPayment(HttpServletRequest request) {
        Context extracted = W3CTraceContextPropagator.getInstance().extract(Context.root(), request, GETTER);
        logger.info("Received traceparent={} parentRemote={}", request.getHeader("traceparent"),
                Span.fromContext(extracted).getSpanContext().isRemote());
        Span server = tracer.spanBuilder("POST /payments")
                .setParent(extracted).setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope scope = server.makeCurrent()) {
            return "approved";
        } finally {
            server.end();
        }
    }

}

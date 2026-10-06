package com.github.yanzord.distributedhttptracing.payment;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PaymentController {

    private final Tracer tracer;

    public PaymentController(Tracer tracer) {
        this.tracer = tracer;
    }

    @PostMapping("/payments")
    public String processPayment() {
        Span server = tracer.spanBuilder("POST /payments")
                .setNoParent().setSpanKind(SpanKind.SERVER).startSpan();
        try (Scope scope = server.makeCurrent()) {
            return "approved";
        } finally {
            server.end();
        }
    }

}

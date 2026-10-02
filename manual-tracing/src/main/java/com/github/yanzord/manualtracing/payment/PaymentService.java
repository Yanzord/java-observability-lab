package com.github.yanzord.manualtracing.payment;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

    private final Tracer tracer;

    public PaymentService(Tracer tracer) {
        this.tracer = tracer;
    }

    public void processPayment() {
        Span span = tracer.spanBuilder("process-payment").startSpan();
        try (Scope scope = span.makeCurrent()) {
            logger.info("Dummy payment approved");
        } finally {
            span.end();
        }
    }

}

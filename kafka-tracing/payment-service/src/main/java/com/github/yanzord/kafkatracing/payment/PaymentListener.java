package com.github.yanzord.kafkatracing.payment;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PaymentListener {

    private static final Logger logger = LoggerFactory.getLogger(PaymentListener.class);

    private final Tracer tracer;

    public PaymentListener(Tracer tracer) {
        this.tracer = tracer;
    }

    @KafkaListener(topics = "orders")
    public void processPayment(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("traceparent");
        String traceparent = header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
        Span consumer = tracer.spanBuilder("orders process")
                .setNoParent().setSpanKind(SpanKind.CONSUMER)
                .setAttribute("order.id", record.value()).startSpan();
        try (Scope scope = consumer.makeCurrent()) {
            logger.info("Payment approved orderId={} traceparent={} traceId={} partition={} offset={}",
                    record.value(), traceparent, consumer.getSpanContext().getTraceId(),
                    record.partition(), record.offset());
        } finally {
            consumer.end();
        }
    }

}

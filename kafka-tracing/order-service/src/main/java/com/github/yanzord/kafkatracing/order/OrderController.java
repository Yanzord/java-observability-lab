package com.github.yanzord.kafkatracing.order;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
public class OrderController {

    private final Tracer tracer;
    private final KafkaTemplate<String, String> kafka;

    public OrderController(Tracer tracer, KafkaTemplate<String, String> kafka) {
        this.tracer = tracer;
        this.kafka = kafka;
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public String createOrder() throws Exception {
        String orderId = UUID.randomUUID().toString();
        Span server = tracer.spanBuilder("POST /orders")
                .setNoParent().setSpanKind(SpanKind.SERVER)
                .setAttribute("order.id", orderId).startSpan();
        try (Scope scope = server.makeCurrent()) {
            Span producer = tracer.spanBuilder("orders send").setSpanKind(SpanKind.PRODUCER)
                    .setAttribute("order.id", orderId).startSpan();
            try (Scope producerScope = producer.makeCurrent()) {
                ProducerRecord<String, String> record = new ProducerRecord<>("orders", orderId, orderId);
                kafka.send(record).get(15, TimeUnit.SECONDS);
                return orderId;
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                producer.recordException(exception);
                producer.setStatus(StatusCode.ERROR);
                throw exception;
            } finally {
                producer.end();
            }
        } finally {
            server.end();
        }
    }

}

package com.github.yanzord.kafkatracing.order;

import com.github.yanzord.kafkatracing.payment.PaymentListener;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KafkaTracingTests {

    @Test
    void producerHasLocalParentButSendsNoTraceHeaders() throws Exception {
        var exporter = new CaptureExporter();
        try (var provider = provider(exporter)) {
            KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
            when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
                ProducerRecord<String, String> record = invocation.getArgument(0);
                assertEquals("orders", record.topic());
                assertEquals(record.key(), record.value());
                assertFalse(record.headers().iterator().hasNext());
                assertTrue(Span.current().getSpanContext().isValid());
                return CompletableFuture.completedFuture(new SendResult<>(record, null));
            });
            Context original = Context.current();
            String orderId = new OrderController(provider.get("order-test"), kafka).createOrder();
            assertSame(original, Context.current());
            verify(kafka).send(any(ProducerRecord.class));
            assertEquals(2, exporter.spans.size());
            SpanData producer = exporter.spans.get(0);
            SpanData server = exporter.spans.get(1);
            assertEquals(SpanKind.PRODUCER, producer.getKind());
            assertEquals(SpanKind.SERVER, server.getKind());
            assertFalse(server.getParentSpanContext().isValid());
            assertEquals(server.getTraceId(), producer.getTraceId());
            assertEquals(server.getSpanId(), producer.getParentSpanId());
            assertEquals(orderId, producer.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("order.id")));
        }
    }

    @Test
    void consumerStartsIndependentRootsAndRestoresReusedWorkerContext() throws Exception {
        var orderExporter = new CaptureExporter();
        var paymentExporter = new CaptureExporter();
        try (var orderProvider = provider(orderExporter);
             var paymentProvider = provider(paymentExporter);
             var worker = Executors.newSingleThreadExecutor()) {
            Span producer = orderProvider.get("order-test").spanBuilder("orders send")
                    .setSpanKind(SpanKind.PRODUCER).startSpan();
            var listener = new PaymentListener(paymentProvider.get("payment-test"));
            Context original = worker.submit(Context::current).get();
            Span unrelated = paymentProvider.get("payment-test").spanBuilder("unrelated").startSpan();
            worker.submit(() -> {
                try (Scope scope = unrelated.makeCurrent()) {
                    Context previous = Context.current();
                    for (int attempt = 0; attempt < 2; attempt++) {
                        listener.processPayment(new ConsumerRecord<>("orders", 0, attempt, "order-" + attempt, "order-" + attempt));
                        assertSame(previous, Context.current());
                    }
                }
            }).get();
            assertSame(original, worker.submit(Context::current).get());
            producer.end();
            unrelated.end();
            List<SpanData> consumers = paymentExporter.spans.stream()
                    .filter(span -> span.getKind() == SpanKind.CONSUMER).toList();
            assertEquals(2, consumers.size());
            for (SpanData consumer : consumers) {
                assertFalse(consumer.getParentSpanContext().isValid());
                assertFalse(consumer.getParentSpanContext().isRemote());
                assertNotEquals(producer.getSpanContext().getTraceId(), consumer.getTraceId());
                assertNotEquals(unrelated.getSpanContext().getTraceId(), consumer.getTraceId());
            }
            assertNotEquals(consumers.get(0).getTraceId(), consumers.get(1).getTraceId());
        }
    }

    @Test
    void failedSendEndsSpansRecordsErrorAndRestoresCaller() {
        var exporter = new CaptureExporter();
        try (var provider = provider(exporter)) {
            KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
            when(kafka.send(any(ProducerRecord.class)))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Broker unavailable")));
            Span caller = provider.get("test").spanBuilder("caller").startSpan();
            try (Scope scope = caller.makeCurrent()) {
                Context previous = Context.current();
                var controller = new OrderController(provider.get("test"), kafka);
                assertThrows(ExecutionException.class, controller::createOrder);
                assertSame(previous, Context.current());
                assertEquals(2, exporter.spans.size());
                assertEquals(StatusCode.ERROR, exporter.spans.get(0).getStatus().getStatusCode());
                assertEquals("exception", exporter.spans.get(0).getEvents().getFirst().getName());
            } finally {
                caller.end();
            }
        }
    }

    private SdkTracerProvider provider(CaptureExporter exporter) {
        return SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    }

    private static class CaptureExporter implements SpanExporter {
        private final List<SpanData> spans = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            this.spans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

}

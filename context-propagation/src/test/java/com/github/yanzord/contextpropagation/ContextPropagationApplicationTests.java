package com.github.yanzord.contextpropagation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextPropagationApplicationTests {

    @Test
    void executorWithoutPropagationCreatesIndependentRootSpans() throws Exception {
        List<SpanData> spans = new ArrayList<>();
        var exporter = new InspectingSpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> completedSpans) {
                spans.addAll(completedSpans);
                return CompletableResultCode.ofSuccess();
            }
        };
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()) {
            Context previous = Context.current();
            var workerParent = ContextPropagationApplication.runWithoutPropagation(
                    provider.get("com.github.yanzord.contextpropagation"));

            assertFalse(workerParent.isValid());
            assertEquals(List.of("process-payment", "create-order"),
                    spans.stream().map(SpanData::getName).toList());
            SpanData payment = spans.get(0);
            SpanData order = spans.get(1);
            assertFalse(payment.getParentSpanContext().isValid());
            assertFalse(order.getParentSpanContext().isValid());
            assertNotEquals(order.getTraceId(), payment.getTraceId());
            assertNotEquals(order.getSpanId(), payment.getSpanId());
            assertSame(previous, Context.current());
        }
    }

    @Test
    void derivingContextDoesNotActivateItAndScopeRestoresItAfterFailure() {
        try (SdkTracerProvider provider = SdkTracerProvider.builder().build()) {
            Context previous = Context.current();
            Span span = provider.get("com.github.yanzord.contextpropagation")
                    .spanBuilder("create-order").startSpan();
            Context derived = previous.with(span);
            try {
                assertSame(previous, Context.current());
                assertEquals(span.getSpanContext(), Span.fromContext(derived).getSpanContext());
                assertThrows(IllegalStateException.class, () -> {
                    try (Scope scope = derived.makeCurrent()) {
                        assertSame(derived, Context.current());
                        assertEquals(span.getSpanContext(), Span.current().getSpanContext());
                        throw new IllegalStateException("Payment failed");
                    }
                });
                assertSame(previous, Context.current());
            } finally {
                span.end();
            }
        }
    }

    @Test
    void synchronousCallsShareTraceAndRestoreContext() {
        List<SpanData> spans = new ArrayList<>();
        SpanProcessor processor = new SpanProcessor() {
            @Override
            public void onStart(Context parentContext, ReadWriteSpan span) {
            }

            @Override
            public boolean isStartRequired() {
                return false;
            }

            @Override
            public void onEnd(ReadableSpan span) {
                spans.add(span.toSpanData());
            }

            @Override
            public boolean isEndRequired() {
                return true;
            }
        };
        try (SdkTracerProvider provider = SdkTracerProvider.builder().addSpanProcessor(processor).build()) {
            Context previous = Context.current();
            var tracer = provider.get("com.github.yanzord.contextpropagation");
            ContextPropagationApplication.runExperiment(tracer);

            assertEquals(List.of("process-payment", "create-order"),
                    spans.stream().map(SpanData::getName).toList());
            SpanData payment = spans.get(0);
            SpanData order = spans.get(1);
            assertEquals(order.getTraceId(), payment.getTraceId());
            assertEquals(order.getSpanId(), payment.getParentSpanId());
            assertNotEquals(order.getSpanId(), payment.getSpanId());
            assertFalse(order.getParentSpanContext().isValid());
            assertSame(previous, Context.current());

            Span caller = tracer.spanBuilder("caller").startSpan();
            try (Scope scope = caller.makeCurrent()) {
                Context callerContext = Context.current();
                ContextPropagationApplication.runExperiment(tracer);
                assertSame(callerContext, Context.current());
                assertEquals(caller.getSpanContext().getSpanId(), spans.get(3).getParentSpanId());
                assertEquals(caller.getSpanContext().getTraceId(), spans.get(3).getTraceId());
            } finally {
                caller.end();
            }
            assertSame(previous, Context.current());
        }
    }
}

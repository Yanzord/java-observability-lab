package com.github.yanzord.contextpropagation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class W3CPropagationExperimentTests {

    private final List<SpanData> spans = new ArrayList<>();

    private SdkTracerProvider createProvider() {
        var exporter = new InspectingSpanExporter() {
            @Override
            public CompletableResultCode export(Collection<SpanData> completedSpans) {
                spans.addAll(completedSpans);
                return CompletableResultCode.ofSuccess();
            }
        };
        return SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
    }

    @Test
    void carrierPreservesTraceIdentityFlagsAndStateWithRemoteParent() {
        try (SdkTracerProvider provider = createProvider()) {
            Context previous = Context.current();
            var ancestor = SpanContext.create("0123456789abcdef0123456789abcdef", "0123456789abcdef",
                    TraceFlags.getSampled(), TraceState.builder().put("vendor", "value").build());
            try (Scope scope = Context.root().with(Span.wrap(ancestor)).makeCurrent()) {
                Context caller = Context.current();
                var carrier = W3CPropagationExperiment.runExperiment(
                        provider.get("com.github.yanzord.contextpropagation"));

                assertEquals(List.of("process-payment", "create-order"),
                        spans.stream().map(SpanData::getName).toList());
                SpanData payment = spans.get(0);
                SpanData order = spans.get(1);
                assertEquals("00-" + order.getTraceId() + "-" + order.getSpanId() + "-01",
                        carrier.get("traceparent"));
                assertEquals("vendor=value", carrier.get("tracestate"));
                assertEquals(order.getTraceId(), payment.getTraceId());
                assertEquals(order.getSpanId(), payment.getParentSpanId());
                assertNotEquals(order.getSpanId(), payment.getSpanId());
                assertTrue(payment.getParentSpanContext().isRemote());
                assertEquals(order.getSpanContext().getTraceFlags(), payment.getParentSpanContext().getTraceFlags());
                assertEquals(ancestor.getTraceState(), payment.getSpanContext().getTraceState());
                assertSame(caller, Context.current());
            }
            assertSame(previous, Context.current());
        }
    }

    @Test
    void absentOrInvalidTraceparentCreatesRootInsteadOfInheritingLocalSpan() {
        try (SdkTracerProvider provider = createProvider()) {
            var tracer = provider.get("com.github.yanzord.contextpropagation");
            Context previous = Context.current();
            Span local = tracer.spanBuilder("unrelated-local-operation").startSpan();
            try (Scope scope = local.makeCurrent()) {
                Context caller = Context.current();
                List<Map<String, String>> carriers = List.of(Map.of(),
                        Map.of("traceparent", "invalid"),
                        Map.of("traceparent", "00-00000000000000000000000000000000-0123456789abcdef-01"),
                        Map.of("traceparent", "00-0123456789abcdef0123456789abcdef-0000000000000000-01"));
                for (var carrier : carriers) {
                    spans.clear();
                    var parent = W3CPropagationExperiment.receivePayment(tracer, carrier);
                    assertFalse(parent.isValid());
                    assertEquals(1, spans.size());
                    SpanData payment = spans.get(0);
                    assertFalse(payment.getParentSpanContext().isValid());
                    assertNotEquals(local.getSpanContext().getTraceId(), payment.getTraceId());
                    assertSame(caller, Context.current());
                }
            } finally {
                local.end();
            }
            assertSame(previous, Context.current());
        }
    }
}

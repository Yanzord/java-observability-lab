package com.github.yanzord.contextpropagation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;

import java.util.HashMap;
import java.util.Map;

class W3CPropagationExperiment {

    private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    static Map<String, String> runExperiment(Tracer tracer) {
        Map<String, String> carrier = new HashMap<>();
        Span order = tracer.spanBuilder("create-order").startSpan();
        try (Scope scope = order.makeCurrent()) {
            W3CTraceContextPropagator.getInstance().inject(Context.current(), carrier, Map::put);
            System.out.println("W3C carrier: " + carrier);
            receivePayment(tracer, carrier);
            return carrier;
        } finally {
            order.end();
        }
    }

    static SpanContext receivePayment(Tracer tracer, Map<String, String> carrier) {
        Context previous = Context.current();
        Context extracted = W3CTraceContextPropagator.getInstance().extract(Context.root(), carrier, GETTER);
        SpanContext remoteParent = Span.fromContext(extracted).getSpanContext();
        System.out.println("Extracted parent valid: " + remoteParent.isValid()
                + ", remote: " + remoteParent.isRemote());
        Span payment = tracer.spanBuilder("process-payment").setParent(extracted).startSpan();
        try (Scope scope = payment.makeCurrent()) {
            System.out.println("Payment trace after W3C extraction: " + Span.current().getSpanContext().getTraceId());
            return remoteParent;
        } finally {
            payment.end();
            System.out.println("Receiver context restored: " + (Context.current() == previous));
        }
    }
}

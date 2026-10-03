package com.github.yanzord.contextpropagation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

public class ContextPropagationApplication {

    public static void main(String[] args) {
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(new InspectingSpanExporter()))
                .build()) {
            runExperiment(provider.get("com.github.yanzord.contextpropagation"));
        }
    }

    static void runExperiment(Tracer tracer) {
        Context previous = Context.current();
        Span order = tracer.spanBuilder("create-order").startSpan();
        Context orderContext = previous.with(order);
        System.out.println("Creating a context leaves the current context unchanged: "
                + (Context.current() == previous));
        try (Scope scope = orderContext.makeCurrent()) {
            Span payment = tracer.spanBuilder("process-payment").startSpan();
            try (Scope paymentScope = Context.current().with(payment).makeCurrent()) {
                System.out.println("Current payment span: " + Span.current().getSpanContext().getSpanId());
            } finally {
                payment.end();
            }
            System.out.println("Order context restored: " + (Context.current() == orderContext));
        } finally {
            order.end();
        }
        System.out.println("Previous context restored: " + (Context.current() == previous));
    }
}

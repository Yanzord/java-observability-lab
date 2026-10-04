package com.github.yanzord.contextpropagation;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ContextPropagationApplication {

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        try (SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(new InspectingSpanExporter()))
                .build()) {
            runExperiment(provider.get("com.github.yanzord.contextpropagation"));
            runWithoutPropagation(provider.get("com.github.yanzord.contextpropagation"));
            try (var executor = Executors.newSingleThreadExecutor()) {
                runWithPropagation(provider.get("com.github.yanzord.contextpropagation"), executor,
                        () -> System.out.println("Payment approved"));
                System.out.println("Reused worker has a valid current span: "
                        + executor.submit(() -> Span.current().getSpanContext().isValid()).get());
            }
            W3CPropagationExperiment.runExperiment(provider.get("com.github.yanzord.contextpropagation"));
        }
    }

    static void runWithPropagation(Tracer tracer, ExecutorService executor, Runnable paymentOperation)
            throws InterruptedException, ExecutionException {
        Span order = tracer.spanBuilder("create-order").startSpan();
        try (Scope scope = order.makeCurrent()) {
            Context captured = Context.current();
            executor.submit(() -> {
                Context workerPrevious = Context.current();
                try (Scope propagatedScope = captured.makeCurrent()) {
                    System.out.println("Propagated order span on worker: "
                            + Span.current().getSpanContext().getSpanId());
                    Span payment = tracer.spanBuilder("process-payment").startSpan();
                    try (Scope paymentScope = payment.makeCurrent()) {
                        paymentOperation.run();
                    } finally {
                        payment.end();
                    }
                } finally {
                    System.out.println("Worker context restored: " + (Context.current() == workerPrevious));
                }
            }).get();
        } finally {
            order.end();
        }
    }

    static SpanContext runWithoutPropagation(Tracer tracer) throws InterruptedException, ExecutionException {
        Context previous = Context.current();
        Span order = tracer.spanBuilder("create-order").startSpan();
        try (Scope scope = order.makeCurrent();
             var executor = Executors.newSingleThreadExecutor()) {
            System.out.println("Caller thread: " + Thread.currentThread().getName()
                    + ", current order span: " + Span.current().getSpanContext().getSpanId());
            return executor.submit(() -> {
                SpanContext workerParent = Span.current().getSpanContext();
                System.out.println("Worker thread: " + Thread.currentThread().getName()
                        + ", current span valid before payment: " + workerParent.isValid());
                Span payment = tracer.spanBuilder("process-payment").startSpan();
                try (Scope paymentScope = payment.makeCurrent()) {
                    System.out.println("Worker payment trace: " + Span.current().getSpanContext().getTraceId());
                } finally {
                    payment.end();
                }
                return workerParent;
            }).get();
        } finally {
            order.end();
            System.out.println("Previous context restored after executor: " + (Context.current() == previous));
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

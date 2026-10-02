package com.github.yanzord.manualtracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ManualTracingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ManualTracingApplication.class, args);
    }

    @Bean
    public CommandLineRunner tracingExperiment(Tracer tracer) {
        return args -> {
            Context previousContext = Context.current();
            System.out.println("Before creation: " + Span.fromContext(previousContext).getSpanContext().getSpanId());
            Span span = tracer.spanBuilder("context-experiment").startSpan();
            try {
                Context spanContext = previousContext.with(span);
                System.out.println("Created span: " + span.getSpanContext().getSpanId());
                System.out.println("Span in original context: " + Span.fromContext(previousContext).getSpanContext().getSpanId());
                System.out.println("Span in new context: " + Span.fromContext(spanContext).getSpanContext().getSpanId());
                System.out.println("Before scope: " + Span.current().getSpanContext().getSpanId());
                try (Scope scope = spanContext.makeCurrent()) {
                    System.out.println("Inside scope: " + Span.current().getSpanContext().getSpanId());
                }
                System.out.println("After scope: " + Span.current().getSpanContext().getSpanId());
                System.out.println("Original context restored: " + (Context.current() == previousContext));
                System.out.println("Span still in new context: " + Span.fromContext(spanContext).getSpanContext().getSpanId());
                System.out.println("Recording after scope: " + span.isRecording());
            } finally {
                span.end();
            }
            System.out.println("Recording after end: " + span.isRecording());
        };
    }

}
